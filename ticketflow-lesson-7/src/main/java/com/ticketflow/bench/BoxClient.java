package com.ticketflow.bench;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The handful of housekeeping calls the bench makes to a box between load steps. */
final class BoxClient {

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern IN_FLIGHT = Pattern.compile("\"inFlight\"\\s*:\\s*(\\d+)");
    private static final Pattern FLAT_OBJECT = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SENT = Pattern.compile("\"sent\"\\s*:\\s*(\\d+)");
    private static final Pattern FAILED = Pattern.compile("\"failed\"\\s*:\\s*(\\d+)");

    private final HttpClient http;

    BoxClient(HttpClient http) {
        this.http = http;
    }

    /** Empties the box's hall, zeroes its counters and switches off any fault. */
    void reset(String target) throws IOException, InterruptedException {
        post(target + "/api/admin/reset");
    }

    /** Switches a fault on in one box. Mode is {@code slow} or {@code fail-fast}. */
    void fault(String target, String mode, int factor) throws IOException, InterruptedException {
        post(target + "/api/admin/fault/" + mode + "?factor=" + factor);
    }

    /** Switches the kind of checkout session a box hands out: {@code server} or {@code signed}. */
    void useSessions(String target, String kind) throws IOException, InterruptedException {
        post(target + "/api/admin/checkout/" + kind);
    }

    /** Wipes the box's memory the way a restart would. */
    void restart(String target) throws IOException, InterruptedException {
        post(target + "/api/admin/restart");
    }

    void useStrategy(String balancer, String strategy) throws IOException, InterruptedException {
        post(balancer + "/api/balancer/strategy/" + strategy);
    }

    void resetBalancerCounters(String balancer) throws IOException, InterruptedException {
        post(balancer + "/api/balancer/reset");
    }

    /** How the balancer has split traffic since its counters were last reset. */
    List<BackendShare> shares(String balancer) throws IOException, InterruptedException {
        return parseShares(get(balancer + "/api/balancer"));
    }

    /** Reads the {@code backends} objects out of the balancer's status JSON, whatever order their fields are in. */
    static List<BackendShare> parseShares(String json) {
        List<BackendShare> shares = new ArrayList<>();
        Matcher objects = FLAT_OBJECT.matcher(json);
        while (objects.find()) {
            String object = objects.group();
            Matcher name = NAME.matcher(object);
            Matcher sent = SENT.matcher(object);
            Matcher failed = FAILED.matcher(object);
            if (name.find() && sent.find() && failed.find()) {
                shares.add(new BackendShare(name.group(1), Long.parseLong(sent.group(1)), Long.parseLong(failed.group(1))));
            }
        }
        return shares;
    }

    private void post(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException(url + " answered HTTP " + response.statusCode());
        }
    }

    /** Booking requests the box has received and not yet answered. */
    int inFlight(String target) throws IOException, InterruptedException {
        Matcher matcher = IN_FLIGHT.matcher(get(target + "/api/box"));
        if (!matcher.find()) {
            throw new IOException(target + "/api/box did not report inFlight");
        }
        return Integer.parseInt(matcher.group(1));
    }

    /** Waits until every target has answered everything it accepted. Returns false if it never does. */
    boolean awaitDrained(Iterable<String> targets, Duration limit) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            boolean drained = true;
            for (String target : targets) {
                try {
                    if (inFlight(target) > 0) {
                        drained = false;
                    }
                } catch (IOException unreachable) {
                    drained = false;
                }
            }
            if (drained) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    /** The seat ids one box believes it has sold. */
    Set<String> soldSeatIds(String target, String venueId) throws IOException, InterruptedException {
        return parseSeatIds(get(target + "/api/venues/" + venueId + "/sold"));
    }

    /** Reads a flat JSON array of strings, which is the only shape the sold endpoint returns. */
    static Set<String> parseSeatIds(String jsonArray) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = QUOTED.matcher(jsonArray);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException(url + " answered HTTP " + response.statusCode());
        }
        return response.body();
    }
}
