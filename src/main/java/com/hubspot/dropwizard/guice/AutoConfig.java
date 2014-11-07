package com.hubspot.dropwizard.guice;

import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import io.dropwizard.Bundle;
import io.dropwizard.cli.Command;
import io.dropwizard.cli.ConfiguredCommand;
import io.dropwizard.cli.EnvironmentCommand;
import io.dropwizard.lifecycle.Managed;
import io.dropwizard.servlets.tasks.Task;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import com.google.common.base.Preconditions;
import com.google.inject.Injector;
import com.sun.jersey.spi.inject.InjectableProvider;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.Path;
import javax.ws.rs.ext.Provider;
import java.util.Collection;
import java.util.Set;

public class AutoConfig {

	final Logger logger = LoggerFactory.getLogger(AutoConfig.class);

	private Reflections reflections;

	public AutoConfig(String... basePackages) {
		Preconditions.checkArgument(basePackages.length > 0);
		
		ConfigurationBuilder cfgBldr = new ConfigurationBuilder();
		FilterBuilder filterBuilder = new FilterBuilder();
		for (String basePkg : basePackages) {
			cfgBldr.addUrls(ClasspathHelper.forPackage(basePkg));
			filterBuilder.include(FilterBuilder.prefix(basePkg));
		}

		cfgBldr.filterInputsBy(filterBuilder).setScanners(
				new SubTypesScanner(), new TypeAnnotationsScanner());
		this.reflections = new Reflections(cfgBldr);
	}

	public void run(Environment environment, Injector injector) {
		addHealthChecks(environment, injector);
		addProviders(environment, injector);
		addInjectableProviders(environment, injector);
		addResources(environment, injector);
		addTasks(environment, injector);
		addManaged(environment, injector);
	}

	public void initialize(Bootstrap<?> bootstrap, Injector injector) {
		addBundles(bootstrap, injector);
        addCommands(bootstrap, injector);
	}

	private void addManaged(Environment environment, Injector injector) {
		Set<Class<? extends Managed>> managedClasses = reflections
				.getSubTypesOf(Managed.class);
		for (Class<? extends Managed> managed : managedClasses) {
			environment.lifecycle().manage(injector.getInstance(managed));
			logger.info("Added managed: {}", managed);
		}
	}

	private void addTasks(Environment environment, Injector injector) {
		Set<Class<? extends Task>> taskClasses = reflections
				.getSubTypesOf(Task.class);
		for (Class<? extends Task> task : taskClasses) {
			environment.admin().addTask(injector.getInstance(task));
			logger.info("Added task: {}", task);
		}
	}

	private void addHealthChecks(Environment environment, Injector injector) {
		Set<Class<? extends InjectableHealthCheck>> healthCheckClasses = reflections
				.getSubTypesOf(InjectableHealthCheck.class);
		for (Class<? extends InjectableHealthCheck> healthCheck : healthCheckClasses) {
            InjectableHealthCheck instance = injector.getInstance(healthCheck);
            environment.healthChecks().register(instance.getName(), instance);
			logger.info("Added injectableHealthCheck: {}", healthCheck);
		}
	}

	@SuppressWarnings("rawtypes")
	private void addInjectableProviders(Environment environment,
			Injector injector) {
		Set<Class<? extends InjectableProvider>> injectableProviders = reflections
				.getSubTypesOf(InjectableProvider.class);
		for (Class<? extends InjectableProvider> injectableProvider : injectableProviders) {
			environment.jersey().register(injectableProvider);
			logger.info("Added injectableProvider: {}", injectableProvider);
		}
	}

	private void addProviders(Environment environment, Injector injector) {
		Set<Class<?>> providerClasses = reflections
				.getTypesAnnotatedWith(Provider.class);
		for (Class<?> provider : providerClasses) {
			environment.jersey().register(provider);
			logger.info("Added provider class: {}", provider);
		}
	}

	private void addResources(Environment environment, Injector injector) {
		Set<Class<?>> resourceClasses = reflections
				.getTypesAnnotatedWith(Path.class);
		for (Class<?> resource : resourceClasses) {
			environment.jersey().register(resource);
			logger.info("Added resource class: {}", resource);
		}
	}

	private void addBundles(Bootstrap<?> bootstrap, Injector injector) {
		Set<Class<? extends Bundle>> bundleClasses = reflections
				.getSubTypesOf(Bundle.class);
		for (Class<? extends Bundle> bundle : bundleClasses) {
			bootstrap.addBundle(injector.getInstance(bundle));
			logger.info("Added bundle class {} during bootstrap", bundle);
		}
	}

    private void addCommands(Bootstrap<?> bootstrap, Injector injector) {
        Collection<Object> existingCommands = Collections2.transform(bootstrap.getCommands(), new Function<Command, Object>() {
            @Override
            public Class<? extends Command> apply(Command input) {
                return input.getClass();
            }
        });

        Set<Class<? extends Command>> commandClasses = reflections.getSubTypesOf(Command.class);
        //The SubTypesScanner does not resolve the entire ancestry of a class
        //This won't get subtyped Commands.  If this becomes a problem, a
        //replacement Scanner could be written.  It is getting a bit ridiculous
        //with all the Injected commands as well.
        commandClasses.addAll(reflections.getSubTypesOf(ConfiguredCommand.class));
        commandClasses.addAll(reflections.getSubTypesOf(EnvironmentCommand.class));
        commandClasses.addAll(reflections.getSubTypesOf(InjectedCommand.class));
        commandClasses.addAll(reflections.getSubTypesOf(InjectedConfiguredCommand.class));
        commandClasses.addAll(reflections.getSubTypesOf(InjectedEnvironmentCommand.class));
        for(Class<? extends Command> command : commandClasses) {
            if(existingCommands.contains(command)) continue;
            bootstrap.addCommand(injector.getInstance(command));
            logger.info("Added command class {} durring bootstrap", command);
        }
    }
}
