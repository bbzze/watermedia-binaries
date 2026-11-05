package org.watermedia.api.platform.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.watermedia.WaterMediaConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Extraction without a child process. iOS cannot start another program, so yt-dlp can never run there. This backend
 * asks NewPipeExtractor (a pure Java library) instead and writes the answer in the JSON shape yt-dlp prints, so
 * {@link YtDlpPlatform#extract} keeps choosing qualities, audio tracks, headers and expiry exactly as before.
 * <p>
 * Engine choice (system property {@code watermedia.youtube.engine}):
 * {@code auto} (default) runs yt-dlp first and switches to this backend for good once yt-dlp cannot be started,
 * {@code newpipe} always uses this backend, {@code ytdlp} never does.
 */
final class NewPipeBackend {
    private static final Marker IT = MarkerManager.getMarker(NewPipeBackend.class.getSimpleName());
    private static final String ENGINE_PROPERTY = "watermedia.youtube.engine";
    // HEADERS java.net.http REFUSES TO SET; THE CONNECTION LAYER OWNS THEM
    private static final Set<String> RESTRICTED_HEADERS = Set.of("connection", "content-length", "expect", "host", "upgrade", "accept-encoding");
    private static final String FALLBACK_IOS_UA = "com.google.ios.youtube/20.03.02 (iPhone16,2; U; CPU iOS 18_2_1 like Mac OS X;)";
    private static final String FALLBACK_ANDROID_UA = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip";
    private static final String FALLBACK_WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";

    private static volatile boolean ytDlpDead;
    private static boolean initialized;

    private NewPipeBackend() {}

    // ==========================================================================
    // ENGINE CHOICE
    // ==========================================================================

    /** The sites this backend can extract: YouTube, SoundCloud and the {@code ytsearchN:} pseudo-URL. */
    static boolean supports(final String url) {
        if (url == null) return false;
        if (url.startsWith("ytsearch")) return true;
        final String host;
        try {
            host = URI.create(url).getHost();
        } catch (final IllegalArgumentException e) {
            return false;
        }
        if (host == null) return false;
        final String h = host.toLowerCase(Locale.ROOT);
        return h.equals("youtu.be") || h.equals("youtube.com") || h.endsWith(".youtube.com")
                || h.equals("soundcloud.com") || h.endsWith(".soundcloud.com") || h.equals("snd.sc");
    }

    /** {@code true} when yt-dlp must not be tried: forced by the property, or it already failed to start. */
    static boolean useFirst() {
        final String engine = System.getProperty(ENGINE_PROPERTY, "auto").toLowerCase(Locale.ROOT);
        return engine.equals("newpipe") || ytDlpDead;
    }

    /** {@code true} when yt-dlp was disabled by the property, so a failure must not fall back. */
    static boolean ytDlpOnly() {
        return System.getProperty(ENGINE_PROPERTY, "auto").equalsIgnoreCase("ytdlp");
    }

    /** The error {@link YtDlpPlatform} throws when it cannot start or provision the yt-dlp program at all. */
    static boolean isLaunchFailure(final IOException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            final String message = t.getMessage();
            if (message != null && (message.startsWith("Could not start yt-dlp") || message.startsWith("Failed to provision yt-dlp"))) return true;
        }
        return false;
    }

    static void ytDlpUnavailable(final IOException cause) {
        if (ytDlpDead) return;
        ytDlpDead = true;
        LOGGER.warn(IT, "yt-dlp cannot be started on this device ({}), extracting with NewPipeExtractor from now on", cause.getMessage());
    }

    // ==========================================================================
    // EXTRACTION
    // ==========================================================================

    /** Same contract as {@link YtDlpPlatform#info}: yt-dlp's JSON for a link, or its flat search result for a {@code ytsearchN:} spec. */
    static JsonObject info(final String url, final List<String> extraArgs, final int timeoutMs) throws IOException {
        try {
            init(timeoutMs);
            if (url.startsWith("ytsearch")) return search(url);
            return media(url);
        } catch (final IOException e) {
            throw e;
        } catch (final Exception | LinkageError e) {
            throw classify(e);
        }
    }

    private static synchronized void init(final int timeoutMs) {
        if (initialized) return;
        NewPipe.init(new Http(Math.max(5_000, timeoutMs)));
        initialized = true;
        LOGGER.info(IT, "NewPipeExtractor ready");
    }

    private static JsonObject media(final String url) throws Exception {
        final StreamInfo info = StreamInfo.getInfo(url);
        final String id = info.getId();
        final boolean live = String.valueOf(info.getStreamType()).contains("LIVE");

        final JsonObject media = new JsonObject();
        media.addProperty("id", id);
        media.addProperty("title", info.getName());
        media.addProperty("webpage_url", info.getUrl() != null ? info.getUrl() : url);
        if (info.getUploaderName() != null) media.addProperty("uploader", info.getUploaderName());
        if (info.getDuration() > 0) media.addProperty("duration", info.getDuration());
        media.addProperty("is_live", live);

        final Object description = optional(info, "getDescription");
        final Object text = description == null ? null : optional(description, "getContent");
        if (text instanceof String s && !s.isBlank()) media.addProperty("description", s);

        if (optional(info, "getAgeLimit") instanceof Number age) media.addProperty("age_limit", age.intValue());

        final Object date = optional(info, "getUploadDate");
        final Object when = date == null ? null : optional(date, "offsetDateTime");
        if (when instanceof OffsetDateTime odt) media.addProperty("timestamp", odt.toEpochSecond());

        final String thumbnail = thumbnail(info, id);
        if (thumbnail != null) media.addProperty("thumbnail", thumbnail);

        final JsonArray formats = new JsonArray();
        int videoCount = 0;
        if (info.getVideoOnlyStreams() != null) for (final VideoStream v: info.getVideoOnlyStreams()) {
            if (addVideo(formats, v, true)) videoCount++;
        }
        if (info.getVideoStreams() != null) for (final VideoStream v: info.getVideoStreams()) {
            if (addVideo(formats, v, false)) videoCount++;
        }
        int audioCount = 0;
        if (info.getAudioStreams() != null) for (final AudioStream a: info.getAudioStreams()) {
            if (addAudio(formats, a)) audioCount++;
        }

        // A MANIFEST IS ONLY ADDED WHEN THERE IS NOTHING ELSE TO PLAY (LIVE STREAMS), OTHERWISE IT WOULD SHOW UP AS AN EXTRA QUALITY OF UNKNOWN SIZE
        final String hls = info.getHlsUrl();
        if (hls != null && !hls.isBlank() && (live || videoCount == 0)) {
            final JsonObject f = new JsonObject();
            f.addProperty("url", hls);
            f.addProperty("vcodec", "avc1");
            f.addProperty("acodec", "mp4a");
            f.addProperty("protocol", "m3u8_native");
            f.add("http_headers", headers(null, true));
            formats.add(f);
            videoCount++;
        }

        LOGGER.info(IT, "NewPipe extracted '{}': {} video, {} audio streams{}", info.getName(), videoCount, audioCount, live ? " (live)" : "");
        if (formats.isEmpty()) throw new IOException("NewPipe found no playable streams for " + url);
        media.add("formats", formats);
        return media;
    }

    private static JsonObject search(final String spec) throws Exception {
        final int colon = spec.indexOf(':');
        if (colon < 0) throw new IOException("Malformed search: " + spec);
        int limit = 10;
        try {
            limit = Integer.parseInt(spec.substring("ytsearch".length(), colon));
        } catch (final NumberFormatException ignored) {}
        final String query = spec.substring(colon + 1);

        final StreamingService youtube = ServiceList.YouTube;
        final SearchQueryHandler handler = youtube.getSearchQHFactory().fromQuery(query, List.of("videos"), "");
        final SearchInfo result = SearchInfo.getInfo(youtube, handler);

        final JsonArray entries = new JsonArray();
        for (final InfoItem item: result.getRelatedItems()) {
            if (item.getInfoType() != InfoItem.InfoType.STREAM) continue;
            final JsonObject entry = new JsonObject();
            entry.addProperty("ie_key", "Youtube");
            final String id = queryParam(item.getUrl(), "v");
            if (id != null) entry.addProperty("id", id);
            entry.addProperty("url", item.getUrl());
            entry.addProperty("title", item.getName());
            entries.add(entry);
            if (entries.size() >= limit) break;
        }
        final JsonObject out = new JsonObject();
        out.add("entries", entries);
        return out;
    }

    // ==========================================================================
    // FORMAT MAPPING
    // ==========================================================================

    private static boolean addVideo(final JsonArray formats, final VideoStream v, final boolean videoOnly) {
        final String url = playable(v.getContent(), v.isUrl(), String.valueOf(v.getDeliveryMethod()));
        if (url == null) return false;
        final JsonObject f = new JsonObject();
        f.addProperty("url", url);
        f.addProperty("vcodec", v.getCodec() == null || v.getCodec().isBlank() ? "avc1" : v.getCodec());
        f.addProperty("acodec", videoOnly ? "none" : "mp4a");
        if (v.getHeight() > 0) f.addProperty("height", v.getHeight());
        if (v.getFps() > 0) f.addProperty("fps", v.getFps());
        if (v.getBitrate() > 0) f.addProperty("tbr", v.getBitrate() / 1000d);
        f.addProperty("protocol", String.valueOf(v.getDeliveryMethod()).equals("HLS") ? "m3u8_native" : "https");
        f.add("http_headers", headers(url, false));
        formats.add(f);
        return true;
    }

    private static boolean addAudio(final JsonArray formats, final AudioStream a) {
        final String url = playable(a.getContent(), a.isUrl(), String.valueOf(a.getDeliveryMethod()));
        if (url == null) return false;
        final JsonObject f = new JsonObject();
        f.addProperty("url", url);
        f.addProperty("vcodec", "none");
        f.addProperty("acodec", "mp4a");
        if (a.getAverageBitrate() > 0) f.addProperty("abr", a.getAverageBitrate());
        f.addProperty("protocol", String.valueOf(a.getDeliveryMethod()).equals("HLS") ? "m3u8_native" : "https");

        // SEVERAL LANGUAGES: ONE LANGUAGE TAG PER AUDIO TRACK, THE ORIGINAL RANKED FIRST. THE ACCESSORS ARE LOOKED UP BY NAME
        // SO AN EXTRACTOR RELEASE WITHOUT THEM JUST MEANS ONE ANONYMOUS AUDIO
        if (optional(a, "getAudioLocale") instanceof Locale locale) {
            f.addProperty("language", locale.toLanguageTag());
            final String type = String.valueOf(optional(a, "getAudioTrackType"));
            f.addProperty("language_preference", switch (type) {
                case "ORIGINAL" -> 10;
                case "DESCRIPTIVE" -> -10;
                case "DUBBED" -> -1;
                default -> 5;
            });
            if (optional(a, "getAudioTrackName") instanceof String name && !name.isBlank()) f.addProperty("format_note", name);
        }
        f.add("http_headers", headers(url, false));
        formats.add(f);
        return true;
    }

    // ONLY ADDRESSES FFMPEG CAN OPEN: A URL, OVER HTTP(S), HLS OR DASH
    private static String playable(final String content, final boolean isUrl, final String delivery) {
        if (!isUrl || content == null || content.isBlank()) return null;
        if (delivery.equals("TORRENT") || delivery.equals("SS")) return null;
        return content;
    }

    private static String thumbnail(final StreamInfo info, final String id) {
        final String page = info.getUrl() == null ? "" : info.getUrl();
        if (id != null && (page.contains("youtube.com") || page.contains("youtu.be"))) return "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
        if (optional(info, "getThumbnails") instanceof List<?> list && !list.isEmpty()) {
            final Object last = list.get(list.size() - 1);
            if (optional(last, "getUrl") instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }

    // GOOGLEVIDEO ANSWERS 403 UNLESS THE PLAYER SENDS WHAT THE EXTRACTING CLIENT SENT; THE URL NAMES THAT CLIENT IN ITS c= PARAMETER
    private static JsonObject headers(final String streamUrl, final boolean hls) {
        final String client = streamUrl == null ? "IOS" : String.valueOf(queryParam(streamUrl, "c")).toUpperCase(Locale.ROOT);
        final JsonObject headers = new JsonObject();
        if (hls || client.startsWith("IOS")) {
            headers.addProperty("User-Agent", userAgent("getIosUserAgent", FALLBACK_IOS_UA));
        } else if (client.startsWith("ANDROID")) {
            headers.addProperty("User-Agent", userAgent("getAndroidUserAgent", FALLBACK_ANDROID_UA));
        } else {
            headers.addProperty("User-Agent", FALLBACK_WEB_UA);
            headers.addProperty("Origin", "https://www.youtube.com");
            headers.addProperty("Referer", "https://www.youtube.com/");
        }
        return headers;
    }

    private static String userAgent(final String method, final String fallback) {
        try {
            final Class<?> helper = Class.forName("org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper");
            final Class<?> localization = Class.forName("org.schabi.newpipe.extractor.localization.Localization");
            final Object ua = helper.getMethod(method, localization).invoke(null, new Object[]{null});
            if (ua instanceof String s && !s.isBlank()) return s;
        } catch (final ReflectiveOperationException | RuntimeException ignored) {}
        return fallback;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // CALLS A GETTER BY NAME; null WHEN THE EXTRACTOR RELEASE HAS NO SUCH ACCESSOR, SO A RENAMED ONE NEVER BREAKS THE BUILD
    private static Object optional(final Object target, final String method) {
        if (target == null) return null;
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static String queryParam(final String url, final String name) {
        if (url == null) return null;
        final int q = url.indexOf('?');
        if (q < 0) return null;
        for (final String pair: url.substring(q + 1).split("&")) {
            final int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) return pair.substring(eq + 1);
        }
        return null;
    }

    // THE MESSAGES MATTER: YouTubePlatform LOOKS FOR "age-restricted" AND FOR THE BOT-CHECK WORDING TO CHOOSE ITS NEXT STEP.
    // THE BOT-CHECK RETRY NEEDS A PROGRAM THAT CANNOT RUN HERE, SO THAT WORDING IS NEVER USED
    private static IOException classify(final Throwable e) {
        final String type = e.getClass().getSimpleName();
        final String text = e.getMessage() == null ? "" : e.getMessage();
        if (type.contains("AgeRestricted")) return new IOException("This video is age-restricted and needs a signed-in session", e);
        if (type.contains("SignInConfirm") || text.toLowerCase(Locale.ROOT).contains("not a bot")) return new IOException("YouTube blocked this request with its bot protection (" + type + ")", e);
        if (type.contains("ReCaptcha")) return new IOException("YouTube rate-limited this request (" + type + ")", e);
        if (type.contains("Geographic")) return new IOException("This video is not available in your country", e);
        if (type.contains("Private")) return new IOException("This video is private", e);
        if (type.contains("ContentNotAvailable") || type.contains("ContentNotSupported")) return new IOException("This content is not available: " + text, e);
        return new IOException("NewPipe could not extract this link (" + type + "): " + text, e);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    // THE EXTRACTOR NEEDS ITS OWN HTTP CLIENT; THIS ONE IS THE JDK'S
    private static final class Http extends Downloader {
        private final HttpClient client;
        private final Duration timeout;

        Http(final int timeoutMs) {
            this.timeout = Duration.ofMillis(Math.max(timeoutMs, WaterMediaConfig.network.timeout));
            this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(this.timeout).build();
        }

        @Override
        public Response execute(final Request request) throws IOException, ReCaptchaException {
            final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.url())).timeout(this.timeout);
            final byte[] data = request.dataToSend();
            builder.method(request.httpMethod(), data == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(data));
            for (final Map.Entry<String, List<String>> header: request.headers().entrySet()) {
                if (RESTRICTED_HEADERS.contains(header.getKey().toLowerCase(Locale.ROOT))) continue;
                for (final String value: header.getValue()) builder.header(header.getKey(), value);
            }

            final HttpResponse<byte[]> response;
            try {
                response = this.client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while contacting " + request.url(), e);
            }
            if (response.statusCode() == 429) throw new ReCaptchaException("Too many requests", request.url());
            return new Response(response.statusCode(), "", response.headers().map(), new String(response.body(), StandardCharsets.UTF_8), response.uri().toString());
        }
    }
}
