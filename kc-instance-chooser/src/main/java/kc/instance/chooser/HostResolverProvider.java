package kc.instance.chooser;

import org.keycloak.provider.Provider;

public interface HostResolverProvider extends Provider {
    HostResolver getResolver();
}