package local.personalmemo.auth.domain;

public record GoogleProfile(
    String subject, String email, boolean emailVerified, String displayName) {}
