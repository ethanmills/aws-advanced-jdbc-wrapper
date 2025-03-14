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

import static org.junit.platform.commons.util.AnnotationUtils.findAnnotation;

import integration.refactored.TestEnvironmentFeatures;
import integration.refactored.container.TestEnvironment;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

class DisableOnTestFeatureCondition implements ExecutionCondition {

  public DisableOnTestFeatureCondition() {}

  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {

    Set<TestEnvironmentFeatures> features =
        TestEnvironment.getCurrent().getInfo().getRequest().getFeatures();

    boolean disabled =
        findAnnotation(context.getElement(), DisableOnTestFeature.class)
            .map(
                annotation -> {
                  if (annotation == null || annotation.value() == null) {
                    return true;
                  }
                  return Arrays.stream(annotation.value()).anyMatch(features::contains);
                }) //
            .orElse(false);

    if (disabled) {
      return ConditionEvaluationResult.disabled("Disabled by @DisableOnTestFeature");
    }
    return ConditionEvaluationResult.enabled("Test enabled");
  }
}
