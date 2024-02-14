/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2022-2024 The JReleaser authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jreleaser.extensions.cdevents;

import dev.cdevents.CDEvents;
import dev.cdevents.constants.CDEventConstants;
import dev.cdevents.events.PipelinerunFinishedCDEvent;
import dev.cdevents.events.PipelinerunStartedCDEvent;
import dev.cdevents.events.TaskrunFinishedCDEvent;
import dev.cdevents.events.TaskrunStartedCDEvent;
import io.cloudevents.CloudEvent;
import io.cloudevents.SpecVersion;
import io.cloudevents.core.message.MessageWriter;
import io.cloudevents.http.HttpMessageFactory;
import org.jreleaser.extensions.api.workflow.WorkflowListener;
import org.jreleaser.model.api.JReleaserContext;
import org.jreleaser.model.api.announce.Announcer;
import org.jreleaser.model.api.assemble.Assembler;
import org.jreleaser.model.api.catalog.Cataloger;
import org.jreleaser.model.api.deploy.Deployer;
import org.jreleaser.model.api.distributions.Distribution;
import org.jreleaser.model.api.download.Downloader;
import org.jreleaser.model.api.hooks.ExecutionEvent;
import org.jreleaser.model.api.packagers.Packager;
import org.jreleaser.model.api.release.Releaser;
import org.jreleaser.model.api.upload.Uploader;
import org.jreleaser.util.Errors;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;

import static org.jreleaser.model.api.hooks.ExecutionEvent.Type.BEFORE;
import static org.jreleaser.model.api.hooks.ExecutionEvent.Type.SUCCESS;
import static org.jreleaser.util.Env.envKey;
import static org.jreleaser.util.Env.sysKey;
import static org.jreleaser.util.StringUtils.getClassNameForLowerCaseHyphenSeparatedName;
import static org.jreleaser.util.StringUtils.getPropertyName;
import static org.jreleaser.util.StringUtils.isBlank;
import static org.jreleaser.util.StringUtils.isNotBlank;

/**
 * @author Andres Almiray
 * @since 1.0.0
 */
public final class CDEventsWorkflowListener implements WorkflowListener {
    private static final String MODE = "mode";
    private static final String CONTINUE_ON_ERROR = "continueOnError";
    private static final String CONTENT_LENGTH = "content-length";
    private static final String CDEVENTS_PREFIX = "cdevents.";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_SUBJECT_ID = "subject.id";
    private static final String KEY_SUBJECT_URL = "subject.url";
    private static final String KEY_SUBJECT_SOURCE = "subject.source";
    private static final String KEY_SUBJECT_PIPELINE_NAME = "subject.pipeline.name";
    private static final String KEY_SUBJECT_PIPELINE_RUN_ID = "subject.pipeline.run.id";
    private static final String KEY_SUBJECT_ERRORS = "subject.errors";
    private static final String KEY_ENDPOINT = "endpoint";

    private boolean enabled;
    private boolean continueOnError;
    private ExecutionEvent.Type outcome;
    private final Deque<String> taskNames = new ArrayDeque<>();

    private String subjectId;
    private String subjectPipelineName;
    private String subjectPipelineRunId;
    private String subjectErrors;
    private String endpoint;
    private URI source;
    private URI subjectSource;
    private String subjectUrl;
    private Mode mode;

    @Override
    public void init(JReleaserContext context, Map<String, Object> properties) {
        System.setProperty("org.slf4j.simpleLogger.dev.cdevents", "debug");

        if (properties.containsKey(CONTINUE_ON_ERROR)) {
            continueOnError = isTrue(properties.get(CONTINUE_ON_ERROR));
        }

        subjectId = resolvePropertyValue(properties, KEY_SUBJECT_ID);
        subjectPipelineName = resolvePropertyValue(properties, KEY_SUBJECT_PIPELINE_NAME);
        subjectPipelineRunId = resolvePropertyValue(properties, KEY_SUBJECT_PIPELINE_RUN_ID);
        subjectErrors = resolvePropertyValue(properties, KEY_SUBJECT_ERRORS);
        endpoint = resolvePropertyValue(properties, KEY_ENDPOINT);
        String source = resolvePropertyValue(properties, KEY_SOURCE);
        String subjectUrl = resolvePropertyValue(properties, KEY_SUBJECT_URL);
        String subjectSource = resolvePropertyValue(properties, KEY_SUBJECT_SOURCE);
        String mode = resolvePropertyValue(properties, MODE);
        this.mode = Mode.of(isNotBlank(mode) ? mode : Mode.CREATE.name());

        Errors errors = new Errors();
        if (isBlank(source)) {
            errors.configuration(RB.$("ERROR_environment_property_check", args(KEY_SOURCE)));
        } else {
            this.source = URI.create(source);
        }
        if (isBlank(subjectId)) {
            errors.configuration(RB.$("ERROR_environment_property_check", args(KEY_SUBJECT_ID)));
        }
        if (isBlank(subjectUrl)) {
            errors.configuration(RB.$("ERROR_environment_property_check", args(KEY_SUBJECT_URL)));
        } else {
            this.subjectUrl = URI.create(subjectUrl).toString();
        }
        if (isBlank(subjectSource)) {
            errors.configuration(RB.$("ERROR_environment_property_check", args(KEY_SUBJECT_SOURCE)));
        } else {
            this.subjectSource = URI.create(subjectSource);
        }
        if (isBlank(subjectPipelineName)) {
            errors.configuration(RB.$("ERROR_environment_property_check", args(KEY_SUBJECT_PIPELINE_NAME)));
        }
        if (isBlank(subjectPipelineRunId)) {
            errors.configuration(RB.$("ERROR_environment_property_check", args(KEY_SUBJECT_PIPELINE_RUN_ID)));
        }
        if (isBlank(subjectErrors)) {
            errors.configuration(RB.$("ERROR_environment_property_check", args(KEY_SUBJECT_ERRORS)));
        }
        if (isBlank(endpoint)) {
            errors.configuration(RB.$("ERROR_environment_property_check", args(KEY_ENDPOINT)));
        }

        if (errors.hasConfigurationErrors()) {
            enabled = false;
            errors.logErrors(context.getLogger());
            context.getLogger().warn(RB.$("extension.disabled"));
        } else {
            enabled = true;
        }
    }

    @Override
    public boolean isContinueOnError() {
        return false;
    }

    @Override
    public void onSessionStart(JReleaserContext context) {
        if (!enabled) return;

        if (Mode.CREATE == mode) {
            PipelinerunStartedCDEvent cdEvent = new PipelinerunStartedCDEvent();
            cdEvent.setSource(source);
            cdEvent.setSubjectId(subjectId);
            cdEvent.setSubjectSource(subjectSource);
            cdEvent.setSubjectUrl(subjectUrl);
            cdEvent.setSubjectPipelineName(subjectPipelineName);
            sendEvent(context, CDEvents.cdEventAsCloudEvent(cdEvent));
        } else {
            sendTaskrunStartedCDEvent(context,
                context.getModel().getProject().getName() + "-" +
                    context.getModel().getProject().version());
        }
    }

    @Override
    public void onSessionEnd(JReleaserContext context) {
        if (!enabled) return;

        if (Mode.CREATE == mode) {
            PipelinerunFinishedCDEvent cdEvent = new PipelinerunFinishedCDEvent();
            cdEvent.setSource(source);
            cdEvent.setSubjectId(subjectId);
            cdEvent.setSubjectSource(subjectSource);
            cdEvent.setSubjectUrl(subjectUrl);
            cdEvent.setSubjectPipelineName(subjectPipelineName);
            cdEvent.setSubjectErrors(subjectErrors);

            if (null != outcome) {
                switch (outcome) {
                    case SUCCESS:
                        cdEvent.setSubjectOutcome(CDEventConstants.Outcome.SUCCESS.getOutcome());
                        break;
                    case FAILURE:
                        cdEvent.setSubjectOutcome(CDEventConstants.Outcome.FAILURE.getOutcome());
                        break;
                }
            } else {
                cdEvent.setSubjectOutcome(CDEventConstants.Outcome.SUCCESS.getOutcome());
            }

            sendEvent(context, CDEvents.cdEventAsCloudEvent(cdEvent));
        } else {
            sendTaskrunFinishedCDEvent(context, null != outcome ? outcome : SUCCESS);
        }
    }

    @Override
    public void onWorkflowStep(ExecutionEvent event, JReleaserContext context) {
        if (BEFORE != event.getType()) {
            outcome = event.getType();
        }

        sendTaskrunCDEvent(context, event, event.getName());
    }

    @Override
    public void onAnnounceStep(ExecutionEvent event, JReleaserContext context, Announcer announcer) {
        sendTaskrunCDEvent(context, event, event.getName() + ":" + announcer.getName());
    }

    @Override
    public void onAssembleStep(ExecutionEvent event, JReleaserContext context, Assembler assembler) {
        sendTaskrunCDEvent(context, event, event.getName() + ":" + assembler.getType() + ":" + assembler.getName());
    }

    @Override
    public void onCatalogStep(ExecutionEvent event, JReleaserContext context, Cataloger cataloger) {
        sendTaskrunCDEvent(context, event, event.getName() + ":" + cataloger.getGroup() + ":" + cataloger.getType());
    }

    @Override
    public void onDeployStep(ExecutionEvent event, JReleaserContext context, Deployer deployer) {
        sendTaskrunCDEvent(context, event, event.getName() + ":" + deployer.getGroup() + ":" + deployer.getType() + ":" + deployer.getName());
    }

    @Override
    public void onDownloadStep(ExecutionEvent event, JReleaserContext context, Downloader downloader) {
        sendTaskrunCDEvent(context, event, event.getName() + ":" + downloader.getType() + ":" + downloader.getName());
    }

    @Override
    public void onUploadStep(ExecutionEvent event, JReleaserContext context, Uploader uploader) {
        sendTaskrunCDEvent(context, event, event.getName() + ":" + uploader.getType() + ":" + uploader.getName());
    }

    @Override
    public void onReleaseStep(ExecutionEvent event, JReleaserContext context, Releaser releaser) {
        sendTaskrunCDEvent(context, event, event.getName() + ":" + releaser.getName());
    }

    @Override
    public void onDistributionStart(JReleaserContext context, Distribution distribution) {
        sendTaskrunCDEvent(context, ExecutionEvent.before(distribution.getName()), distribution.getName());
    }

    @Override
    public void onDistributionEnd(JReleaserContext context, Distribution distribution) {
        sendTaskrunFinishedCDEvent(context, null != outcome ? outcome : SUCCESS);
    }

    @Override
    public void onPackagerPrepareStep(ExecutionEvent event, JReleaserContext context, Distribution distribution, Packager packager) {
        sendTaskrunCDEvent(context, event, distribution.getName() + ":" + event.getName() + ":" + packager.getType());
    }

    @Override
    public void onPackagerPackageStep(ExecutionEvent event, JReleaserContext context, Distribution distribution, Packager packager) {
        sendTaskrunCDEvent(context, event, distribution.getName() + ":" + event.getName() + ":" + packager.getType());
    }

    @Override
    public void onPackagerPublishStep(ExecutionEvent event, JReleaserContext context, Distribution distribution, Packager packager) {
        sendTaskrunCDEvent(context, event, distribution.getName() + ":" + event.getName() + ":" + packager.getType());
    }

    private static boolean isTrue(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean) return (Boolean) o;
        return "true".equalsIgnoreCase(String.valueOf(o).trim());
    }

    private void sendTaskrunCDEvent(JReleaserContext context, ExecutionEvent event, String taskName) {
        if (BEFORE == event.getType()) {
            sendTaskrunStartedCDEvent(context, taskName);
        } else {
            sendTaskrunFinishedCDEvent(context, event.getType());
        }
    }

    private void sendTaskrunStartedCDEvent(JReleaserContext context, String taskName) {
        if (!enabled) return;

        TaskrunStartedCDEvent cdEvent = new TaskrunStartedCDEvent();
        cdEvent.setSource(source);
        cdEvent.setSubjectId(subjectId);
        cdEvent.setSubjectSource(subjectSource);
        cdEvent.setSubjectUrl(subjectUrl);
        cdEvent.setSubjectPipelineRunId(subjectPipelineRunId);
        cdEvent.setSubjectTaskName(taskName);
        taskNames.addFirst(taskName);
        sendEvent(context, CDEvents.cdEventAsCloudEvent(cdEvent));
    }

    private void sendTaskrunFinishedCDEvent(JReleaserContext context, ExecutionEvent.Type eventType) {
        if (!enabled) return;

        outcome = eventType;

        TaskrunFinishedCDEvent cdEvent = new TaskrunFinishedCDEvent();
        cdEvent.setSource(source);
        cdEvent.setSubjectId(subjectId);
        cdEvent.setSubjectSource(subjectSource);
        cdEvent.setSubjectUrl(subjectUrl);
        cdEvent.setSubjectErrors(subjectErrors);
        cdEvent.setSubjectPipelineRunId(subjectPipelineRunId);
        cdEvent.setSubjectTaskName(taskNames.removeFirst());

        if (SUCCESS == eventType) {
            cdEvent.setSubjectOutcome(CDEventConstants.Outcome.SUCCESS.getOutcome());
        } else {
            cdEvent.setSubjectOutcome(CDEventConstants.Outcome.FAILURE.getOutcome());
        }

        sendEvent(context, CDEvents.cdEventAsCloudEvent(cdEvent));
    }

    private void sendEvent(JReleaserContext context, CloudEvent cloudEvent) {
        try {
            doSendEvent(cloudEvent);
        } catch (IOException e) {
            context.getLogger().error(RB.$("ERROR_unexpected_error"), e);
            if (!continueOnError) {
                throw new IllegalStateException(e);
            }
        }
    }

    private void doSendEvent(CloudEvent cloudEvent) throws IOException {
        URL url = new URL(endpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setDoInput(true);

        connection.addRequestProperty("Ce-Id", cloudEvent.getId());
        connection.addRequestProperty("Ce-Specversion", SpecVersion.V1.toString());
        connection.addRequestProperty("Ce-Source", cloudEvent.getSource().toString());
        connection.addRequestProperty("Ce-Type", cloudEvent.getType());
        connection.addRequestProperty("Content-Type", "application/json");

        createMessageWriter(connection)
            .writeBinary(cloudEvent);
    }

    private static MessageWriter createMessageWriter(HttpURLConnection httpUrlConnection) {
        return HttpMessageFactory.createWriter(
            httpUrlConnection::setRequestProperty,
            body -> {
                try {
                    if (body != null) {
                        httpUrlConnection.setRequestProperty(CONTENT_LENGTH, String.valueOf(body.length));
                        try (OutputStream outputStream = httpUrlConnection.getOutputStream()) {
                            outputStream.write(body);
                        }
                    } else {
                        httpUrlConnection.setRequestProperty(CONTENT_LENGTH, "0");
                    }
                } catch (IOException t) {
                    throw new UncheckedIOException(t);
                }
            });
    }

    private static Object[] args(String key) {
        return new String[]{
            key,
            propKey(key),
            sysKey(CDEVENTS_PREFIX + key),
            envKey(CDEVENTS_PREFIX + key)
        };
    }

    private String resolvePropertyValue(Map<String, Object> properties, String key) {
        String pkey = propKey(key);
        if (properties.containsKey(pkey)) return String.valueOf(properties.get(pkey));

        String skey = sysKey(CDEVENTS_PREFIX + key);
        if (System.getProperties().containsKey(skey)) return System.getProperty(skey);

        return System.getenv(envKey(CDEVENTS_PREFIX + key));
    }

    private static String propKey(String key) {
        return getPropertyName(getClassNameForLowerCaseHyphenSeparatedName(key.replaceAll("\\.", "-")));
    }

    enum Mode {
        CREATE,
        JOIN;

        public static Mode of(String str) {
            if (isBlank(str)) return null;
            return Mode.valueOf(str.toUpperCase(Locale.ENGLISH).trim());
        }
    }
}
