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

package google.registry.flows;

import static com.google.common.io.BaseEncoding.base64;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.flows.EppXmlTransformer.marshal;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.LogsSubject.assertAboutLogs;
import static google.registry.testing.TestDataHelper.loadFile;
import static google.registry.testing.TestLogHandlerUtils.findFirstLogRecordWithMessagePrefix;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com.google.common.base.Splitter;
import com.google.common.testing.TestLogHandler;
import google.registry.flows.EppException.UnimplementedExtensionException;
import google.registry.flows.EppTestComponent.FakeServerTridProvider;
import google.registry.flows.FlowModule.EppExceptionInProviderException;
import google.registry.model.eppcommon.Trid;
import google.registry.model.eppoutput.EppOutput;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.eppoutput.Result;
import google.registry.model.eppoutput.Result.Code;
import google.registry.monitoring.whitebox.BigQueryMetricsEnqueuer;
import google.registry.monitoring.whitebox.EppMetric;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.MockitoJUnitRule;
import google.registry.testing.ShardableTestCase;
import google.registry.util.Clock;
import google.registry.xml.ValidationMode;
import java.util.List;
import java.util.Map;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.joda.time.DateTime;
import org.json.simple.JSONValue;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Matchers;
import org.mockito.Mock;

/** Unit tests for {@link EppController}. */
@RunWith(JUnit4.class)
public class EppControllerTest extends ShardableTestCase {

  @Rule public AppEngineRule appEngineRule = new AppEngineRule.Builder().withDatastore().build();
  @Rule public final MockitoJUnitRule mocks = MockitoJUnitRule.create();

  @Mock SessionMetadata sessionMetadata;
  @Mock TransportCredentials transportCredentials;
  @Mock EppMetrics eppMetrics;
  @Mock BigQueryMetricsEnqueuer bigQueryMetricsEnqueuer;
  @Mock FlowComponent.Builder flowComponentBuilder;
  @Mock FlowComponent flowComponent;
  @Mock FlowRunner flowRunner;
  @Mock EppOutput eppOutput;
  @Mock EppResponse eppResponse;
  @Mock Result result;

  private static final DateTime START_TIME = DateTime.parse("2016-09-01T00:00:00Z");

  private final Clock clock = new FakeClock(START_TIME);
  private final TestLogHandler logHandler = new TestLogHandler();

  /**
   * Hold a strong reference to the logger whose output is being intercepted to prevent it being
   * GCed.
   */
  private final Logger loggerToIntercept = Logger.getLogger(EppController.class.getCanonicalName());

  private final String domainCreateXml = loadFile(getClass(), "domain_create_prettyprinted.xml");

  private EppController eppController;

  @Before
  public void setUp() throws Exception {
    loggerToIntercept.addHandler(logHandler);

    when(sessionMetadata.getClientId()).thenReturn("some-client");
    when(flowComponentBuilder.flowModule(Matchers.any())).thenReturn(flowComponentBuilder);
    when(flowComponentBuilder.build()).thenReturn(flowComponent);
    when(flowComponent.flowRunner()).thenReturn(flowRunner);
    when(eppOutput.isResponse()).thenReturn(true);
    when(eppOutput.getResponse()).thenReturn(eppResponse);
    when(eppResponse.getResult()).thenReturn(result);
    when(result.getCode()).thenReturn(Code.SUCCESS_WITH_NO_MESSAGES);

    eppController = new EppController();
    eppController.eppMetricBuilder = EppMetric.builderForRequest("request-id-1", clock);
    when(flowRunner.run(eppController.eppMetricBuilder)).thenReturn(eppOutput);
    eppController.bigQueryMetricsEnqueuer = bigQueryMetricsEnqueuer;
    eppController.flowComponentBuilder = flowComponentBuilder;
    eppController.eppMetrics = eppMetrics;
    eppController.serverTridProvider = new FakeServerTridProvider();
  }

  @After
  public void tearDown() {
    loggerToIntercept.removeHandler(logHandler);
  }

  @Test
  public void testMarshallingUnknownError() throws Exception {
    marshal(
        EppController.getErrorResponse(
            Result.create(Code.COMMAND_FAILED), Trid.create(null, "server-trid")),
        ValidationMode.STRICT);
  }

  @Test
  public void testHandleEppCommand_unmarshallableData_exportsMetric() throws Exception {
    eppController.handleEppCommand(
        sessionMetadata,
        transportCredentials,
        EppRequestSource.UNIT_TEST,
        false,
        false,
        new byte[0]);

    ArgumentCaptor<EppMetric> metricCaptor = ArgumentCaptor.forClass(EppMetric.class);
    verify(bigQueryMetricsEnqueuer).export(metricCaptor.capture());
    EppMetric metric = metricCaptor.getValue();
    assertThat(metric.getRequestId()).isEqualTo("request-id-1");
    assertThat(metric.getStartTimestamp()).isEqualTo(START_TIME);
    assertThat(metric.getEndTimestamp()).isEqualTo(clock.nowUtc());
    assertThat(metric.getClientId()).hasValue("some-client");
    assertThat(metric.getPrivilegeLevel()).hasValue("NORMAL");
    assertThat(metric.getStatus()).hasValue(Code.SYNTAX_ERROR);
  }

  @Test
  public void testHandleEppCommand_regularEppCommand_exportsBigQueryMetric() throws Exception {
    eppController.handleEppCommand(
        sessionMetadata,
        transportCredentials,
        EppRequestSource.UNIT_TEST,
        false,
        true,
        domainCreateXml.getBytes(UTF_8));

    ArgumentCaptor<EppMetric> metricCaptor = ArgumentCaptor.forClass(EppMetric.class);
    verify(bigQueryMetricsEnqueuer).export(metricCaptor.capture());
    EppMetric metric = metricCaptor.getValue();
    assertThat(metric.getRequestId()).isEqualTo("request-id-1");
    assertThat(metric.getStartTimestamp()).isEqualTo(START_TIME);
    assertThat(metric.getEndTimestamp()).isEqualTo(clock.nowUtc());
    assertThat(metric.getClientId()).hasValue("some-client");
    assertThat(metric.getPrivilegeLevel()).hasValue("SUPERUSER");
    assertThat(metric.getStatus()).hasValue(Code.SUCCESS_WITH_NO_MESSAGES);
    assertThat(metric.getEppTarget()).hasValue("example.tld");
  }

  @Test
  public void testHandleEppCommand_regularEppCommand_exportsEppMetrics() throws Exception {
    createTld("tld");
    // Note that some of the EPP metric fields, like # of attempts and command name, are set in
    // FlowRunner, not EppController, and since FlowRunner is mocked out for these tests they won't
    // actually get values.
    EppMetric.Builder metricBuilder =
        EppMetric.builderForRequest("request-id-1", clock)
            .setClientId("some-client")
            .setEppTarget("example.tld")
            .setStatus(Code.SUCCESS_WITH_NO_MESSAGES)
            .setTld("tld")
            .setPrivilegeLevel("SUPERUSER");
    eppController.handleEppCommand(
        sessionMetadata,
        transportCredentials,
        EppRequestSource.UNIT_TEST,
        false,
        true,
        domainCreateXml.getBytes(UTF_8));

    EppMetric expectedMetric = metricBuilder.build();
    verify(eppMetrics).incrementEppRequests(eq(expectedMetric));
    verify(eppMetrics).recordProcessingTime(eq(expectedMetric));
  }

  @Test
  public void testHandleEppCommand_dryRunEppCommand_doesNotExportMetric() throws Exception {
    eppController.handleEppCommand(
        sessionMetadata,
        transportCredentials,
        EppRequestSource.UNIT_TEST,
        true,
        true,
        domainCreateXml.getBytes(UTF_8));

    verifyZeroInteractions(bigQueryMetricsEnqueuer);
    verifyZeroInteractions(eppMetrics);
  }

  @Test
  public void testHandleEppCommand_unmarshallableData_loggedAtInfo_withJsonData() throws Exception {
    eppController.handleEppCommand(
        sessionMetadata,
        transportCredentials,
        EppRequestSource.UNIT_TEST,
        false,
        false,
        "GET / HTTP/1.1\n\n".getBytes(UTF_8));

    assertAboutLogs().that(logHandler)
        .hasLogAtLevelWithMessage(INFO, "EPP request XML unmarshalling failed");
    LogRecord logRecord =
        findFirstLogRecordWithMessagePrefix(logHandler, "EPP request XML unmarshalling failed");
    List<String> messageParts = Splitter.on('\n').splitToList(logRecord.getMessage());
    assertThat(messageParts.size()).isAtLeast(2);
    Map<String, Object> json = parseJsonMap(messageParts.get(1));
    assertThat(json).containsEntry("clientId", "some-client");
    assertThat(json).containsEntry("resultCode", 2001L);  // Must be Long to compare equal.
    assertThat(json).containsEntry("resultMessage", "Command syntax error");
    assertThat(json)
        .containsEntry("xmlBytes", base64().encode("GET / HTTP/1.1\n\n".getBytes(UTF_8)));
  }

  @Test
  public void testHandleEppCommand_throwsEppException_loggedAtInfo() throws Exception {
    when(flowRunner.run(eppController.eppMetricBuilder))
        .thenThrow(new UnimplementedExtensionException());
    eppController.handleEppCommand(
        sessionMetadata,
        transportCredentials,
        EppRequestSource.UNIT_TEST,
        false,
        true,
        domainCreateXml.getBytes(UTF_8));
    assertAboutLogs().that(logHandler)
        .hasLogAtLevelWithMessage(INFO, "Flow returned failure response");
    LogRecord logRecord =
        findFirstLogRecordWithMessagePrefix(logHandler, "Flow returned failure response");
    assertThat(logRecord.getThrown()).isInstanceOf(UnimplementedExtensionException.class);
  }

  @Test
  public void testHandleEppCommand_throwsEppExceptionInProviderException_loggedAtInfo()
      throws Exception {
    when(flowRunner.run(eppController.eppMetricBuilder))
        .thenThrow(new EppExceptionInProviderException(new UnimplementedExtensionException()));
    eppController.handleEppCommand(
        sessionMetadata,
        transportCredentials,
        EppRequestSource.UNIT_TEST,
        false,
        true,
        domainCreateXml.getBytes(UTF_8));
    assertAboutLogs().that(logHandler)
        .hasLogAtLevelWithMessage(INFO, "Flow returned failure response");
    LogRecord logRecord =
        findFirstLogRecordWithMessagePrefix(logHandler, "Flow returned failure response");
    assertThat(logRecord.getThrown()).isInstanceOf(EppExceptionInProviderException.class);
  }

  @Test
  public void testHandleEppCommand_throwsRuntimeException_loggedAtSevere() throws Exception {
    when(flowRunner.run(eppController.eppMetricBuilder)).thenThrow(new IllegalStateException());
    eppController.handleEppCommand(
        sessionMetadata,
        transportCredentials,
        EppRequestSource.UNIT_TEST,
        false,
        true,
        domainCreateXml.getBytes(UTF_8));
    assertAboutLogs().that(logHandler)
        .hasLogAtLevelWithMessage(SEVERE, "Unexpected failure in flow execution");
    LogRecord logRecord =
        findFirstLogRecordWithMessagePrefix(logHandler, "Unexpected failure in flow execution");
    assertThat(logRecord.getThrown()).isInstanceOf(IllegalStateException.class);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseJsonMap(String json) throws Exception {
    return (Map<String, Object>) JSONValue.parseWithException(json);
  }
}
