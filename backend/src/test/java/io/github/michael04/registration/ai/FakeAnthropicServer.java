package io.github.michael04.registration.ai;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A local stand-in for the Anthropic Messages API (JDK built-in HTTP server, localhost only).
 * Tests queue canned responses; the real SDK sends real HTTP requests to it, so request building,
 * response parsing and error handling are exercised without any external call or API key.
 */
final class FakeAnthropicServer implements AutoCloseable {

	record Response(int status, String body, Map<String, String> headers, Duration delay) {
	}

	record Recorded(String method, String path, Map<String, List<String>> headers, String body) {

		String header(String name) {
			return headers.entrySet().stream()
				.filter(e -> e.getKey().equalsIgnoreCase(name))
				.flatMap(e -> e.getValue().stream())
				.findFirst()
				.orElse(null);
		}

	}

	private final HttpServer server;

	private final ConcurrentLinkedQueue<Response> queue = new ConcurrentLinkedQueue<>();

	private final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());

	FakeAnthropicServer() {
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		server.setExecutor(Executors.newCachedThreadPool());
		server.createContext("/", this::handle);
		server.start();
	}

	String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	FakeAnthropicServer enqueue(Response... responses) {
		queue.addAll(List.of(responses));
		return this;
	}

	/** Drops replies nobody asked for, so one test's leftovers can never be served to the next test. */
	void clearQueued() {
		queue.clear();
	}

	List<Recorded> requests() {
		return List.copyOf(requests);
	}

	private void handle(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
				Map.copyOf(exchange.getRequestHeaders()), body));
		Response response = queue.poll();
		if (response == null) {
			response = error(500, "api_error", "FakeAnthropicServer: no response queued");
		}
		if (!response.delay().isZero()) {
			try {
				Thread.sleep(response.delay().toMillis());
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
		byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		response.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
		try {
			exchange.sendResponseHeaders(response.status(), bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		}
		catch (IOException ex) {
			// Client already gave up (timeout tests); nothing to do.
		}
		finally {
			exchange.close();
		}
	}

	@Override
	public void close() {
		server.stop(0);
	}

	// ---- canned responses -------------------------------------------------------------

	/** A normal Messages API response whose text block contains {@code textJson}. */
	static Response message(String stopReason, String textJson) {
		return withBlocks(stopReason, List.of(textBlock(textJson)), null);
	}

	/** Like {@link #message} but with an (empty, display-omitted) thinking block first, as Haiku 5.5 sends. */
	static Response messageWithThinking(String textJson) {
		return withBlocks("end_turn", List.of("{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig\"}", textBlock(textJson)),
				null);
	}

	static Response refusal() {
		return withBlocks("refusal", List.of(),
				"{\"type\":\"refusal\",\"category\":\"cyber\",\"explanation\":\"declined\"}");
	}

	static Response error(int status, String type, String message) {
		String body = "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}}";
		return new Response(status, body, Map.of("retry-after-ms", "1"), Duration.ZERO);
	}

	static Response delayed(Response response, Duration delay) {
		return new Response(response.status(), response.body(), response.headers(), delay);
	}

	private static String textBlock(String textJson) {
		return "{\"type\":\"text\",\"text\":" + jsonString(textJson) + "}";
	}

	private static Response withBlocks(String stopReason, List<String> blocks, String stopDetails) {
		String body = "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-haiku-5-5\","
				+ "\"content\":[" + String.join(",", blocks) + "],"
				+ "\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,"
				+ "\"stop_details\":" + (stopDetails == null ? "null" : stopDetails) + ","
				+ "\"usage\":{\"input_tokens\":1200,\"output_tokens\":180,\"cache_creation_input_tokens\":0,"
				+ "\"cache_read_input_tokens\":0,\"service_tier\":\"standard\"}}";
		return new Response(200, body, Map.of(), Duration.ZERO);
	}

	static String jsonString(String raw) {
		StringBuilder sb = new StringBuilder("\"");
		for (char c : raw.toCharArray()) {
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					}
					else {
						sb.append(c);
					}
				}
			}
		}
		return sb.append('"').toString();
	}

}
