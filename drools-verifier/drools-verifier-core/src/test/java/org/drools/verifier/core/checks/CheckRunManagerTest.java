/*
 * Copyright 2018 Red Hat, Inc. and/or its affiliates.
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

package org.drools.verifier.core.checks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.drools.verifier.api.reporting.CheckType;
import org.drools.verifier.api.reporting.Issue;
import org.drools.verifier.api.reporting.Severity;
import org.drools.verifier.core.AnalyzerConfigurationMock;
import org.drools.verifier.core.cache.RuleInspectorCache;
import org.drools.verifier.core.cache.inspectors.RuleInspector;
import org.drools.verifier.core.checks.base.Check;
import org.drools.verifier.core.checks.base.CheckFactory;
import org.drools.verifier.core.checks.base.CheckRunManager;
import org.drools.verifier.core.checks.base.CheckStorage;
import org.drools.verifier.core.checks.base.JavaCheckRunner;
import org.drools.verifier.core.checks.base.SingleCheck;
import org.drools.verifier.core.configuration.AnalyzerConfiguration;
import org.drools.verifier.core.index.model.Rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class CheckRunManagerTest {

    @Spy
    private CheckRunManager checkRunManager = new CheckRunManager(new JavaCheckRunner());

    @Mock
    private RuleInspectorCache cache;

    private RuleInspector ruleInspector1;
    private RuleInspector ruleInspector2;
    private RuleInspector ruleInspector3;
    private ArrayList<RuleInspector> ruleInspectors;
    private CheckStorage checkStorage;

    private AnalyzerConfiguration configuration;

    @BeforeEach
    public void setUp() throws
            Exception {
        configuration = new AnalyzerConfigurationMock();

        checkStorage = new CheckStorage(
                new CheckFactory(configuration) {
                    @Override
                    public HashSet<Check> makeSingleChecks(final RuleInspector ruleInspector) {
                        final HashSet<Check> result = new HashSet<>();
                        result.add(new MockSingleCheck(ruleInspector));
                        return result;
                    }
                });

        ruleInspectors = new ArrayList<>();
        ruleInspector1 = mockRowInspector(1);
        ruleInspectors.add(ruleInspector1);
        ruleInspector2 = mockRowInspector(2);
        ruleInspectors.add(ruleInspector2);
        ruleInspector3 = mockRowInspector(3);
        ruleInspectors.add(ruleInspector3);

        checkRunManager.addChecks(ruleInspector1.getChecks());
        checkRunManager.addChecks(ruleInspector2.getChecks());
        checkRunManager.addChecks(ruleInspector3.getChecks());
    }

    @Test
    void testChecksGetGenerated() throws
            Exception {
        assertThat(ruleInspector1.getChecks()
                .size()).isEqualTo(5);
        assertThat(ruleInspector2.getChecks()
                .size()).isEqualTo(5);
        assertThat(ruleInspector3.getChecks()
                .size()).isEqualTo(5);
    }

    @Test
    void testRemove() throws
            Exception {

        this.checkRunManager.remove(ruleInspector2);

        final Set<Check> checks = ruleInspector1.getChecks();
        assertThat(checks.size()).isEqualTo(3);
        assertThat(ruleInspector2.getChecks()
                .isEmpty()).isTrue();
        assertThat(ruleInspector3.getChecks()
                .size()).isEqualTo(3);
    }

    @Test
    void testRunTests() throws
            Exception {

        for (RuleInspector ruleInspector : cache.all()) {
            assertNoIssues(ruleInspector);
        }

        this.checkRunManager.run(null,
                null);

        for (RuleInspector ruleInspector : cache.all()) {
            assertHasIssues(ruleInspector);
        }
    }

    @Test
    void testOnlyTestChanges() throws
            Exception {
        // First run
        this.checkRunManager.run(null,
                null);

        RuleInspector newRuleInspector = mockRowInspector(3);
        ruleInspectors.add(newRuleInspector);

        this.checkRunManager.addChecks(newRuleInspector.getChecks());

        assertNoIssues(newRuleInspector);

        // Second run
        this.checkRunManager.run(null,
                null);

        assertHasIssues(newRuleInspector);

        assertThat(ruleInspector1.getChecks()
                .size()).isEqualTo(7);
        assertThat(newRuleInspector.getChecks()
                .size()).isEqualTo(7);
    }

    private RuleInspector mockRowInspector(final int rowNumber) {
        return new RuleInspector(new Rule(rowNumber,
                                          configuration),
                                 checkStorage,
                                 cache,
                                 mock(AnalyzerConfiguration.class));
    }

    private void assertHasIssues(final RuleInspector ruleInspector) {
        for (final Check check : ruleInspector.getChecks()) {
            assertThat(check.hasIssues()).isTrue();
        }
    }

    private void assertNoIssues(final RuleInspector ruleInspector) {
        for (final Check check : (ruleInspector.getChecks())) {
            assertThat(check.hasIssues()).isFalse();
        }
    }

    private class MockSingleCheck
            extends SingleCheck {

        public MockSingleCheck(RuleInspector ruleInspector) {
            super(ruleInspector,
                  CheckRunManagerTest.this.configuration,
                  CheckType.REDUNDANT_ROWS);
        }

        @Override
        public boolean check() {
            return hasIssues = true;
        }

        @Override
        protected Severity getDefaultSeverity() {
            return Severity.NOTE;
        }

        @Override
        protected Issue makeIssue(final Severity severity,
                                  final CheckType checkType) {
            return new Issue(severity,
                             checkType,
                             Collections.emptySet());
        }
    }
}