package com.hubspot.dropwizard.guice;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.inject.*;
import com.google.inject.name.Names;
import io.dropwizard.Configuration;
import io.dropwizard.jetty.MutableServletContextHandler;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import net.sourceforge.argparse4j.inf.Namespace;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.ClassUtils;
import org.apache.commons.lang.reflect.FieldUtils;

import javax.servlet.ServletContext;
import java.lang.reflect.Field;
import java.util.List;

import static com.google.common.base.Throwables.propagate;
import static java.lang.String.format;

public class DropwizardEnvironmentModule<T extends Configuration> extends AbstractModule {
	private static final String ILLEGAL_DROPWIZARD_MODULE_STATE = "The dropwizard environment has not been set. This is likely caused by trying to access the dropwizard environment during the bootstrap phase or during a non-configured command.";
	private Optional<T> configuration;
	private Optional<Environment> environment;
    private Optional<Namespace> namespace = Optional.absent();
    private Optional<Bootstrap<T>> bootstrap;
	private Class<? super T> configurationClass;
    private String[] configurationPackages;

	public DropwizardEnvironmentModule(Class<T> configurationClass, String[] configurationPackages) {
		this.configurationClass = configurationClass;
        if(configurationPackages == null) configurationPackages = new String[0];
        this.configurationPackages = ensureTypeInPackages(configurationClass, configurationPackages);
	}

    private String[] ensureTypeInPackages(Class<?> type, String[] packages) {
        String configName = type.getName();
        for(String pack : packages) {
            if(configName.startsWith(pack)) return packages;
        }
        return (String[])ArrayUtils.add(packages, configName);
    }

	@Override
	protected void configure() {
		Provider<T> provider = new CustomConfigurationProvider();
        if(configuration.isPresent()){
            bind(configurationClass).toProvider(provider);
            if (configurationClass != Configuration.class) {
                bind(Configuration.class).toProvider(provider);
            }

            bindConfigs(configurationClass);
        }
        if(environment.isPresent()) {
            bindContext("application", environment.get().getApplicationContext());
        }
	}

    /**
     * Bind some of the context objects to be injectable. Annotated with a {@link com.google.inject.name.Names} to
     * prevent collisions for any that the {@link com.google.inject.servlet.ServletModule} may bind later.
     */
    private void bindContext(String name, MutableServletContextHandler context) {
        bind(ServletContext.class)
            .annotatedWith(Names.named(name))
            .toInstance(context.getServletContext());
    }

    private void bindConfigs(Class<?> config) {
        bindConfigs(config, new String[]{}, Lists.<Class<?>>newArrayList());
    }
    @SuppressWarnings("unchecked")
    private void bindConfigs(Class<?> config, String[] path, List<Class<?>> visited) {
        List<Class<?>> classes = Lists.newArrayList(ClassUtils.getAllSuperclasses(config));
        classes.add(config);
        for(Class<?> cls: classes) {
            for(Field field: cls.getDeclaredFields()) {
                Class<?> type = field.getType();
                visited.add(type);

                String[] subpath = new String[path.length + 1];
                System.arraycopy(path, 0, subpath, 0, path.length);
                subpath[path.length] = field.getName();

                bind(type)
                    .annotatedWith(Names.named(Joiner.on(".").join(subpath)))
                    .toProvider(new ConfigElementProvider(configurationClass, subpath));

                if(!type.isEnum() && isInConfigPackage(type))
                    bindConfigs(type, subpath, visited);
            }
        }
    }

    private boolean isInConfigPackage(Class<?> type) {
        String name = type.getName();
        if(name == null) return false;

        for(String pack : configurationPackages) {
            if(name.startsWith(pack)) return true;
        }
        return false;
    }

    @Deprecated
	public void setEnvironmentData(T configuration, Environment environment) {
        setEnvironmentData(null, environment, configuration);
	}

    public void setEnvironmentData(Bootstrap<T> bootstrap,
                                   Environment environment,
                                   T configuration) {
        this.bootstrap = Optional.fromNullable(bootstrap);
        this.configuration = Optional.fromNullable(configuration);
        this.environment = Optional.fromNullable(environment);
    }

    public void setNamespace(Namespace namespace) {
        this.namespace = Optional.fromNullable(namespace);
    }

	@Provides
	public Environment providesEnvironment() {
		if (environment == null || !environment.isPresent()) {
			throw new ProvisionException(ILLEGAL_DROPWIZARD_MODULE_STATE);
		}
		return environment.get();
	}

    @Provides
    public Namespace providesNamespace() {
        if (namespace == null || !namespace.isPresent()) {
            throw new ProvisionException(ILLEGAL_DROPWIZARD_MODULE_STATE);
        }
        return namespace.get();
    }

    /**
     * Note: This is a raw type.  Guice cannot inject the full type due to type erasure
     */
    @Provides
    public Bootstrap providesBootstrap() {
        if (bootstrap == null || !bootstrap.isPresent()) {
            throw new ProvisionException(ILLEGAL_DROPWIZARD_MODULE_STATE);
        }
        return bootstrap.get();
    }

	private class CustomConfigurationProvider implements Provider<T> {
		@Override
		public T get() {
			if (configuration == null || !configuration.isPresent()) {
				throw new ProvisionException(ILLEGAL_DROPWIZARD_MODULE_STATE);
			}
			return configuration.get();
		}
	}

    private class ConfigElementProvider<U> implements Provider<U> {
        private final Field[] path;

        public ConfigElementProvider(Class<T> configCls, String[] path) {
            this.path = new Field[path.length];

            Class<?> cls = configCls;
            for(int i=0; i<path.length; i++) {
                this.path[i] = findField(cls, path[i]);
                cls = this.path[i].getType();
            }
        }

        private Field findField(final Class<?> cls, String name) {
            Field f = null;
            Class<?> search = cls;
            do {
                f = FieldUtils.getDeclaredField(search, name, true);
                if(f != null)
                    return f;
                else
                    search = search.getSuperclass();

            } while(!search.equals(Object.class));

            throw new IllegalStateException(format("Unable to find field %s on %s", name, cls.getName()));
        }

        @Override
        public U get() {
            Object obj = configuration.get();
            for(Field field: path) {
                try {
                    obj = field.get(obj);
                    if (obj == null) {
                        return null; // Should cause an injection exception
                    }

                } catch(IllegalAccessException e) {
                    throw propagate(e);
                }
            }

            return (U) obj;
        }
    }
}
