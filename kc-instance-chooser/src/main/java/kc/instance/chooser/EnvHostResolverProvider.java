package kc.instance.chooser;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

public class EnvHostResolverProvider implements HostResolverProvider {

    private Map<String, HostRealmIdp> map;

    public EnvHostResolverProvider() {
        this.map = Arrays.stream(System.getenv("KC_DOMAIN_HOSTS").split(";"))
                .map(s -> s.split(","))
                .collect(Collectors.toMap(e -> e[0], e -> new HostRealmIdp(e[1], e[2], e.length==4 ? e[3] : null)));
    }

    @Override
    public void close() {
    }

    @Override
    public HostResolver getResolver() {
        // here we resolve using a static map, but we could actually do a REST call or
        // a DB lookup
        return email -> map.get(getDomain(email));
    }

    private static String getDomain(String username) {
        return username.contains("@")
                ? username.substring(username.indexOf("@") + 1)
                : null;
    }
}
