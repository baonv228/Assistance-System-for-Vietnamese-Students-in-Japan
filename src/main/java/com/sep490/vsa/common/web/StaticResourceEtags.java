package com.sep490.vsa.common.web;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

/**
 * Files in static/ (pages, scripts, styles, images; the clean page addresses forward to them) are answered with an
 * ETag made from their bytes and without Last-Modified ({@code spring.web.resources.cache.use-last-modified=false}).
 * The jar running on the server gives every file the same date (1980-02-01) on every deploy, so a browser or Cloudflare
 * asking "changed since the date of my copy?" always heard 304 and kept showing the old page after a deploy. Now a
 * changed file answers 200, an unchanged one still 304, and a copy kept from before (it only knows its date) is
 * downloaded again once.
 */
@Configuration
class StaticResourceEtags {

    @Bean
    static BeanPostProcessor staticResourceEtagGenerator() {
        ContentEtags etags = new ContentEtags();
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof SimpleUrlHandlerMapping mapping) {
                    mapping.getUrlMap().values().forEach(handler -> {
                        if (handler instanceof ResourceHttpRequestHandler resources) {
                            resources.setEtagGenerator(etags::of);
                        }
                    });
                }
                return bean;
            }
        };
    }

    /**
     * Weak ETag of the file's SHA-256 (first 128 bits), kept per file while its date and length stay the same: files in the jar
     * never change while the server runs, files under target/classes (IDE) get a new date when they are rebuilt.
     */
    static final class ContentEtags {
        private record Entry(long lastModified, long length, String etag) {
        }

        private final Map<String, Entry> byUrl = new ConcurrentHashMap<>();

        String of(Resource resource) {
            try {
                String url = resource.getURL().toString();
                long lastModified = resource.lastModified();
                long length = resource.contentLength();
                Entry known = byUrl.get(url);
                if (known != null && known.lastModified() == lastModified && known.length() == length) {
                    return known.etag();
                }
                String etag = hash(resource);
                byUrl.put(url, new Entry(lastModified, length, etag));
                return etag;
            } catch (IOException e) {
                return null; // no ETag: the file is simply sent in full
            }
        }

        private static String hash(Resource resource) throws IOException {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            try (InputStream in = resource.getInputStream()) {
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) > 0; ) {
                    digest.update(buffer, 0, n);
                }
            }
            // weak: Tomcat does not gzip a response with a strong ETag (noCompressionStrongETag), and the gzipped and
            // plain answers are the same file anyway
            return "W/\"" + HexFormat.of().formatHex(digest.digest(), 0, 16) + "\"";
        }
    }
}
