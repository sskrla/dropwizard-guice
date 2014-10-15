package com.hubspot.dropwizard.guice;

import com.google.inject.Inject;
import io.dropwizard.Configuration;
import io.dropwizard.cli.ConfiguredCommand;
import io.dropwizard.setup.Bootstrap;
import net.sourceforge.argparse4j.inf.Namespace;

/**
 * Must be used in conjunction with the GuiceBundle.
 * Will load the configuration based Guice modules and inject itself before running.
 */
public abstract class GuiceConfiguredCommand<T extends Configuration> extends ConfiguredCommand<T> {
    @Inject
    private GuiceBundle<T> init;

    void setInit(GuiceBundle<T> init) {
        this.init = init;
    }


    protected GuiceConfiguredCommand(String name, String description) {
        super(name, description);
    }

    @Override
    final protected void run(Bootstrap<T> bootstrap, Namespace namespace, T configuration) throws Exception {
        if(init != null) init.run(configuration, this);
        execute(bootstrap, namespace, configuration);
    }

    protected abstract void execute(Bootstrap<T> bootstrap, Namespace namespace, T configuration) throws Exception;
}
