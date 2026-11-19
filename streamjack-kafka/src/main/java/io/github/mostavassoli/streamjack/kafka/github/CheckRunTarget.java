package io.github.mostavassoli.streamjack.kafka.github;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import java.util.regex.Pattern;

public record CheckRunTarget(URI apiUrl, String owner, String repository, String headSha, String checkName) {

    public static final String DEFAULT_API_URL = "https://api.github.com";

    private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,38}");
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final int MAX_CHECK_NAME_LENGTH = 100;

    public CheckRunTarget {
        if (apiUrl == null) {
            throw new IllegalArgumentException("GitHub API URL is required");
        }
        if (!apiUrl.isAbsolute()
                || apiUrl.getHost() == null
                || apiUrl.getRawUserInfo() != null
                || apiUrl.getRawQuery() != null
                || apiUrl.getRawFragment() != null) {
            throw new IllegalArgumentException("GitHub API URL must be an absolute URL without credentials or query");
        }
        boolean https = "https".equalsIgnoreCase(apiUrl.getScheme());
        boolean loopbackHttp = "http".equalsIgnoreCase(apiUrl.getScheme()) && LOOPBACK_HOSTS.contains(apiUrl.getHost());
        if (!https && !loopbackHttp) {
            throw new IllegalArgumentException("GitHub API URL must use HTTPS");
        }
        if (owner == null || !OWNER.matcher(owner).matches()) {
            throw new IllegalArgumentException("repository owner is invalid");
        }
        if (repository == null
                || !REPOSITORY.matcher(repository).matches()
                || repository.equals(".")
                || repository.equals("..")) {
            throw new IllegalArgumentException("repository name is invalid");
        }
        if (headSha == null || !SHA.matcher(headSha).matches()) {
            throw new IllegalArgumentException("head SHA must be a full lowercase commit SHA");
        }
        if (checkName == null
                || checkName.isBlank()
                || checkName.length() > MAX_CHECK_NAME_LENGTH
                || checkName.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "check name must be 1-" + MAX_CHECK_NAME_LENGTH + " characters without control characters");
        }
    }

    public static CheckRunTarget of(String apiUrl, String ownerAndRepository, String headSha, String checkName) {
        if (ownerAndRepository == null || ownerAndRepository.chars().filter(c -> c == '/').count() != 1) {
            throw new IllegalArgumentException("repository must have the form owner/name");
        }
        int slash = ownerAndRepository.indexOf('/');
        return new CheckRunTarget(
                parse(apiUrl),
                ownerAndRepository.substring(0, slash),
                ownerAndRepository.substring(slash + 1),
                headSha,
                checkName);
    }

    URI checkRunsUri() {
        String base = apiUrl.toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + "/repos/" + owner + "/" + repository + "/check-runs");
    }

    private static URI parse(String apiUrl) {
        try {
            return new URI(apiUrl == null || apiUrl.isBlank() ? DEFAULT_API_URL : apiUrl.strip());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("GitHub API URL is not a valid URL");
        }
    }
}
