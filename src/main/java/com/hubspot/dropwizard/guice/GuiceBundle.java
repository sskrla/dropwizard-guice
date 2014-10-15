package com.hubspot.dropwizard.guice;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.inject.*;
import com.google.inject.servlet.GuiceFilter;
import com.sun.jersey.api.core.ResourceConfig;
import com.sun.jersey.spi.container.servlet.ServletContainer;
import io.dropwizard.Configuration;
import io.dropwizard.ConfiguredBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.servlet.ServletContextListener;
import java.util.List;

public class GuiceBundle<T extends Configuration> implements ConfiguredBundle<T> {

    final Logger logger = LoggerFactory.getLogger(GuiceBundle.class);

    private final AutoConfig autoConfig;
    private final List<Module> modules;
    private final List<Module> initModules;
    private final List<Function<Injector, ServletContextListener>> contextListenerGenerators;
    private Injector initInjector;
    private Injector injector;
    private DropwizardEnvironmentModule dropwizardEnvironmentModule;
    private Optional<Class<T>> configurationClass;
    private Stage stage;


    public static class Builder<T extends Configuration> {
        private AutoConfig autoConfig;
        private List<Module> initModules = Lists.newArrayList();
        private List<Module> modules = Lists.newArrayList();
        private List<Function<Injector, ServletContextListener>> contextListenerGenerators = Lists.newArrayList();
        private Optional<Class<T>> configurationClass = Optional.<Class<T>>absent();

        /**
         * Add a module to the bundle.
         * Module may be injected with configuration and environment data.
         * This module will NOT be available for other Bundles and Commands
         * initialized with AutoConfig.
         * Modules will also not be available when running Command and ConfiguredCommands.
         * Use the "Injected" versions of these commands if these modules are needed.
         */
        public Builder<T> addModule(Module module) {
            Preconditions.checkNotNull(module);
            modules.add(module);
            return this;
        }

        /**
         * Add a module to the bundle.
         * Module cannot have injections.
         * This module will be available for other Bundles and Commands
         * initialized with AutoConfig.
         */
        public Builder<T> addInitModule(Module module) {
            Preconditions.checkNotNull(module);
            initModules.add(module);
            return this;
        }

        public Builder<T> addServletContextListener(Function<Injector, ServletContextListener> contextListenerGenerator) {
            Preconditions.checkNotNull(contextListenerGenerator);
            contextListenerGenerators.add(contextListenerGenerator);
            return this;
        }

        public Builder<T> setConfigClass(Class<T> clazz) {
            configurationClass = Optional.of(clazz);
            return this;
        }

        public Builder<T> enableAutoConfig(String... basePackages) {
            Preconditions.checkNotNull(basePackages.length > 0);
            Preconditions.checkArgument(autoConfig == null, "autoConfig already enabled!");
            autoConfig = new AutoConfig(basePackages);
            return this;
        }

        public GuiceBundle<T> build() {
            return build(Stage.PRODUCTION);
        }

        public GuiceBundle<T> build(Stage s) {
            return new GuiceBundle<T>(s, autoConfig, modules, initModules, contextListenerGenerators, configurationClass);
        }

    }

    public static <T extends Configuration> Builder<T> newBuilder() {
        return new Builder<T>();
    }

    private GuiceBundle(Stage stage,
                        AutoConfig autoConfig,
                        List<Module> modules,
                        List<Module> initModules,
                        List<Function<Injector, ServletContextListener>> contextListenerGenerators,
                        Optional<Class<T>> configurationClass) {
        Preconditions.checkNotNull(modules);
        Preconditions.checkArgument(!modules.isEmpty());
        Preconditions.checkNotNull(contextListenerGenerators);
        Preconditions.checkNotNull(stage);
        this.modules = modules;
        this.initModules = initModules;
        this.contextListenerGenerators = contextListenerGenerators;
        this.autoConfig = autoConfig;
        this.configurationClass = configurationClass;
        this.stage = stage;
    }

    @Override
    public void initialize(Bootstrap<?> bootstrap) {
        initInjector = Guice.createInjector(this.stage, this.initModules);
        if (autoConfig != null) {
            autoConfig.initialize(bootstrap, initInjector);
        }
    }

    @Override
    public void run(final T configuration, final Environment environment) {
        final GuiceContainer container = initGuice(configuration, environment);

        environment.jersey().replace(new Function<ResourceConfig, ServletContainer>() {
            @Nullable
            @Override
            public ServletContainer apply(ResourceConfig resourceConfig) {
                return container;
            }
        });
        environment.servlets().addFilter("Guice Filter", GuiceFilter.class)
                .addMappingForUrlPatterns(null, false, environment.getApplicationContext().getContextPath() + "*");

        for (Function<Injector, ServletContextListener> generator : contextListenerGenerators) {
            environment.servlets().addServletListeners(generator.apply(injector));
        }

        if (autoConfig != null) {
            autoConfig.run(environment, injector);
        }
    }

    private GuiceContainer initGuice(final T configuration, final Environment environment) {
        GuiceContainer container = new GuiceContainer();
        container.setResourceConfig(environment.jersey().getResourceConfig());

        JerseyContainerModule jerseyContainerModule = new JerseyContainerModule(container);
        if (configurationClass.isPresent()) {
            dropwizardEnvironmentModule = new DropwizardEnvironmentModule<T>(configurationClass.get());
        } else {
            dropwizardEnvironmentModule = new DropwizardEnvironmentModule<Configuration>(Configuration.class);
        }
        dropwizardEnvironmentModule.setEnvironmentData(configuration, environment);

        Injector moduleInjector = initInjector.createChildInjector(dropwizardEnvironmentModule);

        for(Module module: modules)
            moduleInjector.injectMembers(module);

        modules.add(jerseyContainerModule);
        injector = moduleInjector.createChildInjector(modules);
        injector.injectMembers(container);

        return container;
    }

    public Injector getInjector() {
        return (injector != null) ? injector : initInjector;
    }
}
