/*
 *  Copyright (c) 2025, WSO2 LLC. (http://www.wso2.com)
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package io.ballerina.projectservice.extension;

import org.ballerinalang.langserver.commons.registration.BallerinaServerCapability;

import static io.ballerina.projectservice.extension.ProjectService.CAPABILITY_NAME;

/**
 * Represents server capabilities for the Project Service.
 *
 * @since 1.2.0
 */
public class ProjectServiceServerCapabilities extends BallerinaServerCapability {
    public ProjectServiceServerCapabilities() {
        super(CAPABILITY_NAME);
    }
}
