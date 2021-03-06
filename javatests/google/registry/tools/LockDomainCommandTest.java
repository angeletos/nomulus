// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.eppcommon.StatusValue.SERVER_TRANSFER_PROHIBITED;
import static google.registry.testing.DatastoreHelper.newDomainResource;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.JUnitBackports.assertThrows;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link LockDomainCommand}. */
public class LockDomainCommandTest extends EppToolCommandTestCase<LockDomainCommand> {

  @Before
  public void before() {
    eppVerifier.expectSuperuser();
  }

  @Test
  public void testSuccess_sendsCorrectEppXml() throws Exception {
    persistActiveDomain("example.tld");
    runCommandForced("--client=NewRegistrar", "example.tld");
    eppVerifier.verifySent("domain_lock.xml", ImmutableMap.of("DOMAIN", "example.tld"));
  }

  @Test
  public void testSuccess_partiallyUpdatesStatuses() throws Exception {
    persistResource(
        newDomainResource("example.tld")
            .asBuilder()
            .addStatusValue(SERVER_TRANSFER_PROHIBITED)
            .build());
    runCommandForced("--client=NewRegistrar", "example.tld");
    eppVerifier.verifySent("domain_lock_partial_statuses.xml");
  }

  @Test
  public void testSuccess_manyDomains() throws Exception {
    // Create 26 domains -- one more than the number of entity groups allowed in a transaction (in
    // case that was going to be the failure point).
    List<String> domains = new ArrayList<>();
    for (int n = 0; n < 26; n++) {
      String domain = String.format("domain%d.tld", n);
      persistActiveDomain(domain);
      domains.add(domain);
    }
    runCommandForced(
        ImmutableList.<String>builder().add("--client=NewRegistrar").addAll(domains).build());
    for (String domain : domains) {
      eppVerifier.verifySent("domain_lock.xml", ImmutableMap.of("DOMAIN", domain));
    }
  }

  @Test
  public void testFailure_domainDoesntExist() throws Exception {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--client=NewRegistrar", "missing.tld"));
    assertThat(e).hasMessageThat().isEqualTo("Domain 'missing.tld' does not exist");
  }

  @Test
  public void testSuccess_alreadyLockedDomain_performsNoAction() throws Exception {
    persistResource(
        newDomainResource("example.tld")
            .asBuilder()
            .addStatusValues(REGISTRY_LOCK_STATUSES)
            .build());
    runCommandForced("--client=NewRegistrar", "example.tld");
  }

  @Test
  public void testFailure_duplicateDomainsAreSpecified() throws Exception {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--client=NewRegistrar", "dupe.tld", "dupe.tld"));
    assertThat(e).hasMessageThat().isEqualTo("Duplicate domain arguments found: 'dupe.tld'");
  }
}
