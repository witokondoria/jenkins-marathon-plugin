package com.mesosphere.velocity.marathon;

import com.mesosphere.velocity.marathon.exceptions.MarathonFileInvalidException;
import com.mesosphere.velocity.marathon.exceptions.MarathonFileMissingException;
import com.mesosphere.velocity.marathon.fields.MarathonLabel;
import com.mesosphere.velocity.marathon.fields.MarathonUri;
import com.mesosphere.velocity.marathon.interfaces.AppConfig;
import com.mesosphere.velocity.marathon.interfaces.MarathonBuilder;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import mesosphere.marathon.client.utils.MarathonException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class MarathonRecorder extends Recorder implements AppConfig {
    @Extension
    public static final  DescriptorImpl DESCRIPTOR = new DescriptorImpl();
    private static final Logger         LOGGER     = Logger.getLogger(MarathonRecorder.class.getName());
    private final String              url;
    private       List<MarathonUri>   uris;
    private       List<MarathonLabel> labels;
    private       String              appid;
    private       String              docker;
    private       String              filename;

    @DataBoundConstructor
    public MarathonRecorder(final String url) {
        this.url = url;

        this.uris = new ArrayList<MarathonUri>(5);
        this.labels = new ArrayList<MarathonLabel>(5);
    }

    public String getAppid() {
        return appid;
    }

    @DataBoundSetter
    public void setAppid(@Nonnull String appid) {
        this.appid = appid;
    }

    public String getFilename() {
        return filename;
    }

    @DataBoundSetter
    public void setFilename(@Nonnull final String filename) {
        if (filename.trim().length() > 0)
            this.filename = filename;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        /*
         * This does not need any isolation.
         */
        return BuildStepMonitor.NONE;
    }

    @Override
    public boolean needsToRunAfterFinalized() {
        /*
         * This should be run before the build is finalized.
         */
        return false;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {
        final boolean buildSucceed = build.getResult() == null || build.getResult() == Result.SUCCESS;
        final EnvVars envVars      = build.getEnvironment(listener);
        envVars.overrideAll(build.getBuildVariables());

        if (buildSucceed) {
            try {
                final MarathonBuilder builder = MarathonBuilder.getBuilder(this)
                        .setEnvVars(envVars).setWorkspace(build.getWorkspace())
                        .read(this.filename)
                        .build().toFile();

                // update & possible retry
                boolean retry      = true;
                int     retryCount = 0;
                while (retry && retryCount < 3) {
                    try {
                        builder.update();
                        retry = false;
                    } catch (MarathonException e) {
                        // 4xx and 5xx errors are build failures
                        // 409 is app already deployed
                        if (e.getStatus() >= 400 && e.getStatus() < 600 && e.getStatus() != 409) {
                            build.setResult(Result.FAILURE);
                            LOGGER.warning(e.getMessage());
                            retry = false;
                        } else {
                            // retry.
                            retryCount++;
                            Thread.sleep(5000L);    // 5 seconds
                        }
                    }
                }

                if (retry) {
                    build.setResult(Result.FAILURE);
                    LOGGER.warning("Hit max retries while trying to update Marathon application.");
                }
            } catch (MarathonFileMissingException e) {
                // "marathon.json" or whatever does not exist.
                build.setResult(Result.FAILURE);
                LOGGER.warning(e.getMessage());
            } catch (MarathonFileInvalidException e) {
                // file is a directory or something.
                build.setResult(Result.FAILURE);
                LOGGER.warning(e.getMessage());
            }

        }
        return build.getResult() == Result.SUCCESS;
    }

    @Override
    public String getAppId() {
        return this.appid;
    }

    public String getUrl() {
        return url;
    }

    public String getDocker() {
        return docker;
    }

    @DataBoundSetter
    public void setDocker(@Nonnull String docker) {
        this.docker = docker;
    }

    public List<MarathonUri> getUris() {
        return uris;
    }

    @DataBoundSetter
    public void setUris(List<MarathonUri> uris) {
        this.uris = uris;
    }

    public List<MarathonLabel> getLabels() {
        return labels;
    }

    @DataBoundSetter
    public void setLabels(List<MarathonLabel> labels) {
        this.labels = labels;
    }

    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        public DescriptorImpl() {
            load();
        }

        private boolean isUrl(final String url) {
            boolean valid = false;

            if (url != null && url.length() > 0) {
                try {
                    new URL(url);
                    valid = true;
                } catch (MalformedURLException e) {
                    // malformed; ignore
                }
            }

            return valid;
        }

        private FormValidation verifyUrl(final String url) {
            if (!isUrl(url))
                return FormValidation.error("Not a valid URL");
            return FormValidation.ok();
        }

        public FormValidation doCheckUrl(@QueryParameter String value) {
            return verifyUrl(value);
        }

        public FormValidation doCheckUri(@QueryParameter String value) {
            return verifyUrl(value);
        }

        @Override
        public String getDisplayName() {
            return "Marathon Deployment";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }
    }
}