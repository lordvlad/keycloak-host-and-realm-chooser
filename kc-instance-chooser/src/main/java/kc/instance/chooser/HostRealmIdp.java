package kc.instance.chooser;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public record HostRealmIdp(String host, String realm, String idp) {
    public String encode() {
        return Base64.getEncoder().encodeToString(
                (host + "=" + realm + "=" + (idp == null ? "" : idp)).getBytes(StandardCharsets.UTF_8));
    }
}