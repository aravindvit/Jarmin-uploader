package com.jarmin.uploader;

import com.jarmin.uploader.exception.StoreException;

import java.util.ArrayList;
import java.util.List;

/** Describes a stored object: how many chunks it has and its total size. */
record Manifest(int chunkCount, long totalSize, List<Long> chunkSizes) {

    /**
     * Parses the {@code key=value} line-based manifest body produced by the server, e.g.
     * <pre>chunkCount=3
     * totalSize=2560
     * chunkSizes=1024,1024,512</pre>
     */
    static Manifest parse(String body) throws StoreException {
        int chunkCount = -1;
        long totalSize = -1;
        List<Long> sizes = new ArrayList<>();
        for (String line : body.split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            switch (k) {
                case "chunkCount" -> chunkCount = Integer.parseInt(v);
                case "totalSize" -> totalSize = Long.parseLong(v);
                case "chunkSizes" -> {
                    if (!v.isEmpty()) {
                        for (String s : v.split(",")) {
                            sizes.add(Long.parseLong(s.trim()));
                        }
                    }
                }
                default -> { /* ignore unknown fields */ }
            }
        }
        if (chunkCount < 0 || totalSize < 0) {
            throw new StoreException("Malformed manifest: " + body);
        }
        return new Manifest(chunkCount, totalSize, List.copyOf(sizes));
    }
}
