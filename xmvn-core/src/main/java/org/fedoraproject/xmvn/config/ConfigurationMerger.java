/*-
 * Copyright (c) 2013-2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fedoraproject.xmvn.config;

/**
 * Component that can merge XMvn configurations.
 * 
 * @author Mikolaj Izdebski
 */
public interface ConfigurationMerger
{
    /**
     * Merge two configurations, with one with one having precedence in the case of conflict.
     * <p>
     * Caller should not depend on contents of dominant configuration after the merge was attempted as the
     * implementation is free to modify it. Recessive configuration is never changed.
     * 
     * @param dominant the dominant configuration into which the recessive configuration will be merged (may be
     *            {@code null})
     * @param recessive the recessive configuration from which the configuration will inherited (may not be {@code null}
     *            )
     * @return merged configuration (not {@code null}, may be the same as dominant)
     */
    Configuration merge( Configuration dominant, Configuration recessive );
}
