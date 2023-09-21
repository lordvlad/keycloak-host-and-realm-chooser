package kc.instance.chooser;

@FunctionalInterface
public interface HostResolver {
    HostRealmIdp resolve(String domain);
}