/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package integration.refactored.container.condition;

import integration.refactored.container.TestDriver;
import integration.refactored.container.TestEnvironment;
import java.util.logging.Logger;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

public class EnableBasedOnEnvironmentFeatureExtension implements ExecutionCondition {

  private static final Logger LOGGER =
      Logger.getLogger(EnableBasedOnEnvironmentFeatureExtension.class.getName());

  private final TestDriver testDriver;

  public EnableBasedOnEnvironmentFeatureExtension(TestDriver testDriver) {
    this.testDriver = testDriver;
  }

  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    if (!TestEnvironment.getCurrent().isTestDriverAllowed(this.testDriver)) {
      return ConditionEvaluationResult.disabled("Disabled by test environment features.");
    }
    return ConditionEvaluationResult.enabled("Test enabled");
  }
}
