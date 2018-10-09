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

package google.registry.request;

import static com.google.common.base.Predicates.not;
import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.request.HttpException.BadRequestException;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import org.joda.time.DateTime;

/** Utilities for extracting parameters from HTTP requests. */
public final class RequestParameters {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** The standardized request parameter name used by any action taking a tld parameter. */
  public static final String PARAM_TLD = "tld";
  /** The standardized request parameter name used by any action taking multiple tld parameters. */
  public static final String PARAM_TLDS = "tlds";

  /**
   * Returns first GET or POST parameter associated with {@code name}.
   *
   * <p>For example, assume {@code name} is "bar". The following request URIs would cause this
   * method to yield the following results:
   *
   * <ul>
   * <li>/foo?bar=hello → hello
   * <li>/foo?bar=hello&bar=there → hello
   * <li>/foo?bar= → 400 error (empty)
   * <li>/foo?bar=&bar=there → 400 error (empty)
   * <li>/foo → 400 error (absent)
   * </ul>
   *
   * @throws BadRequestException if request parameter is absent or empty
   */
  public static String extractRequiredParameter(HttpServletRequest req, String name) {
    String result = req.getParameter(name);
    if (isNullOrEmpty(result)) {
      throw new BadRequestException("Missing parameter: " + name);
    }
    return result;
  }

  /** Returns the first GET or POST parameter associated with {@code name}. */
  public static Optional<String> extractOptionalParameter(HttpServletRequest req, String name) {
    return Optional.ofNullable(emptyToNull(req.getParameter(name)));
  }

  /**
   * Returns first GET or POST parameter associated with {@code name} as an integer.
   *
   * @throws BadRequestException if request parameter is present but not a valid integer
   */
  public static Optional<Integer> extractOptionalIntParameter(HttpServletRequest req, String name) {
    String stringParam = req.getParameter(name);
    try {
      return isNullOrEmpty(stringParam)
          ? Optional.empty()
          : Optional.of(Integer.valueOf(stringParam));
    } catch (NumberFormatException e) {
      throw new BadRequestException("Expected integer: " + name);
    }
  }

  /**
   * Returns first GET or POST parameter associated with {@code name} as an integer.
   *
   * @throws BadRequestException if request parameter is absent, empty, or not a valid integer
   */
  public static int extractIntParameter(HttpServletRequest req, String name) {
    try {
      return Integer.parseInt(nullToEmpty(req.getParameter(name)));
    } catch (NumberFormatException e) {
      throw new BadRequestException("Expected integer: " + name);
    }
  }

  /**
   * Returns all GET or POST parameters associated with {@code name} (or {@code nameLegacy}).
   *
   * <p>While transitioning from "param=key1&param=key2" to "params=key1,key2" style of set inputing
   * - we will be accepting all options (with a warning for "wrong" uses).
   *
   * <p>TODO(b/78226288): remove transition code (including "legacyName") once there are no more
   * warnings.
   *
   * @param req the request that has the parameter
   * @param name the name of the parameter, should be in plural form (e.g. tlds=com,net)
   * @param legacyName the legacy, singular form, name. Used only while transitioning from the
   *     tld=com&tld=net form, in case we forgot to fix some reference. Null if there was no
   *     singular form to transition from.
   */
  public static ImmutableSet<String> extractSetOfParameters(
      HttpServletRequest req, String name, @Nullable String legacyName) {
    String[] parameters = req.getParameterValues(name);
    if (legacyName != null) {
      String[] legacyParameters = req.getParameterValues(legacyName);
      if (legacyParameters != null) {
        if (parameters == null) {
          logger.atWarning().log(
              "Bad 'set of parameters' input! Used legacy name %s instead of new name %s",
              legacyName, name);
          parameters = legacyParameters;
        } else {
          logger.atSevere().log(
              "Bad 'set of parameters' input! Used both legacy name %s and new name %s! "
                  + "Ignoring lagacy name.",
              legacyName, name);
        }
      }
    }
    if (parameters == null) {
      return ImmutableSet.of();
    }
    if (parameters.length > 1) {
      logger.atWarning().log(
          "Bad 'set of parameters' input! "
              + "Received multiple values instead of single comma-delimited value for parameter %s",
          name);
      return ImmutableSet.copyOf(parameters);
    }
    if (parameters[0].isEmpty()) {
      return ImmutableSet.of();
    }
    return ImmutableSet.copyOf(Splitter.on(',').split(parameters[0]));
  }

  /**
   * Returns all GET or POST parameters associated with {@code name}.
   *
   * <p>While transitioning from "param=key1&param=key2" to "params=key1,key2" style of set inputing
   * - we will be accepting all options (with a warning for "wrong" uses).
   *
   * <p>TODO(b/78226288): remove transition code (including "legacyName") once there are no more
   * warnings.
   */
  public static ImmutableSet<String> extractSetOfParameters(HttpServletRequest req, String name) {
    return extractSetOfParameters(req, name, null);
  }

  /**
   * Returns the first GET or POST parameter associated with {@code name}, absent otherwise.
   *
   * @throws BadRequestException if request parameter named {@code name} is not equal to any of the
   *     values in {@code enumClass}
   */
  public static <C extends Enum<C>> Optional<C> extractOptionalEnumParameter(
      HttpServletRequest req, Class<C> enumClass, String name) {
    String stringParam = req.getParameter(name);
    return isNullOrEmpty(stringParam)
        ? Optional.empty()
        : Optional.of(extractEnumParameter(req, enumClass, name));
  }

  /**
   * Returns the first GET or POST parameter associated with {@code name}.
   *
   * @throws BadRequestException if request parameter named {@code name} is absent, empty, or not
   *     equal to any of the values in {@code enumClass}
   */
  public static <C extends Enum<C>>
      C extractEnumParameter(HttpServletRequest req, Class<C> enumClass, String name) {
    try {
      return Enum.valueOf(enumClass, Ascii.toUpperCase(extractRequiredParameter(req, name)));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(
          String.format("Invalid %s parameter: %s", enumClass.getSimpleName(), name));
    }
  }

  /**
   * Returns first GET or POST parameter associated with {@code name} as a boolean.
   *
   * @throws BadRequestException if request parameter is present but not a valid boolean
   */
  public static Optional<Boolean> extractOptionalBooleanParameter(
      HttpServletRequest req, String name) {
    String stringParam = req.getParameter(name);
    return isNullOrEmpty(stringParam)
        ? Optional.empty()
        : Optional.of(Boolean.valueOf(stringParam));
  }

  /**
   * Returns {@code true} iff the given parameter is present, not empty, and not {@code "false"}.
   *
   * <p>This considers a parameter with a non-existent value true, for situations where the request
   * URI is something like {@code /foo?bar}, where the mere presence of the {@code bar} parameter
   * without a value indicates that it's true.
   */
  public static boolean extractBooleanParameter(HttpServletRequest req, String name) {
    return req.getParameterMap().containsKey(name) && !equalsFalse(req.getParameter(name));
  }

  /**
   * Returns first request parameter associated with {@code name} parsed as an
   * <a href="https://goo.gl/pk5Q2k">ISO 8601</a> timestamp, e.g. {@code 1984-12-18TZ},
   * {@code 2000-01-01T16:20:00Z}.
   *
   * @throws BadRequestException if request parameter named {@code name} is absent, empty, or could
   *     not be parsed as an ISO 8601 timestamp
   */
  public static DateTime extractRequiredDatetimeParameter(HttpServletRequest req, String name) {
    String stringValue = extractRequiredParameter(req, name);
    try {
      return DateTime.parse(stringValue);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Bad ISO 8601 timestamp: " + name);
    }
  }

  /**
   * Returns first request parameter associated with {@code name} parsed as an
   * <a href="https://goo.gl/pk5Q2k">ISO 8601</a> timestamp, e.g. {@code 1984-12-18TZ},
   * {@code 2000-01-01T16:20:00Z}.
   *
   * @throws BadRequestException if request parameter is present but not a valid {@link DateTime}.
   */
  public static Optional<DateTime> extractOptionalDatetimeParameter(
      HttpServletRequest req, String name) {
    String stringParam = req.getParameter(name);
    try {
      return isNullOrEmpty(stringParam)
          ? Optional.empty()
          : Optional.of(DateTime.parse(stringParam));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Bad ISO 8601 timestamp: " + name);
    }
  }

  /**
   * Returns all GET or POST date parameters associated with {@code name}, or an empty set if none.
   *
   * <p>Dates are parsed as an <a href="https://goo.gl/pk5Q2k">ISO 8601</a> timestamp, e.g. {@code
   * 1984-12-18TZ}, {@code 2000-01-01T16:20:00Z}.
   *
   * @throws BadRequestException if one of the parameter values is not a valid {@link DateTime}.
   */
  public static ImmutableSet<DateTime> extractSetOfDatetimeParameters(
      HttpServletRequest req, String name, @Nullable String legacyName) {
    try {
      return extractSetOfParameters(req, name, legacyName)
          .stream()
          .filter(not(String::isEmpty))
          .map(DateTime::parse)
          .collect(toImmutableSet());
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Bad ISO 8601 timestamp: " + name);
    }
  }

  private static boolean equalsFalse(@Nullable String value) {
    return nullToEmpty(value).equalsIgnoreCase("false");
  }

  /**
   * Returns first HTTP header associated with {@code name}.
   *
   * @param name case insensitive header name
   * @throws BadRequestException if request header is absent or empty
   */
  public static String extractRequiredHeader(HttpServletRequest req, String name) {
    String result = req.getHeader(name);
    if (isNullOrEmpty(result)) {
      throw new BadRequestException("Missing header: " + name);
    }
    return result;
  }

  /**
   * Returns an {@link Optional} of the first HTTP header associated with {@code name}, or empty.
   *
   * @param name case insensitive header name
   */
  public static Optional<String> extractOptionalHeader(HttpServletRequest req, String name) {
    return Optional.ofNullable(req.getHeader(name));
  }

  private RequestParameters() {}
}
