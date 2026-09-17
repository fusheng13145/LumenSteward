package com.lumensteward.clawbot.application.safety;

import com.lumensteward.clawbot.infrastructure.config.properties.SafetyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 本地词库内容安全服务（架构 5.2 / SRS FR-09，BR-12 Fail-Closed）。
 *
 * <p>Fail-Closed 语义：词库加载失败（不可用）时，若 {@code safety.fail-closed=true}（默认），
 * 一律判为<b>不通过</b>，宁拒不放行。
 *
 * <p>词库加载顺序：优先 {@code safety.wordlist-path} 配置路径；若不存在，回落到内置默认
 * {@code classpath:safety/wordlist.txt}；两者皆缺失才视为服务不可用。
 */
@Service
public class LocalWordlistSafetyService implements ContentSafetyService {

    private static final Logger log = LoggerFactory.getLogger(LocalWordlistSafetyService.class);

    /** 内置默认词库路径（与 T04 文件清单一致）。 */
    private static final String BUILTIN_WORDLIST = "classpath:safety/wordlist.txt";

    private final SafetyProperties properties;
    private final ResourceLoader resourceLoader;
    private final Set<String> words;
    private final boolean loaded;

    /**
     * 构造器注入（G-14）：内置默认词库路径。
     *
     * @param properties     安全配置
     * @param resourceLoader 资源加载器
     */
    @Autowired
    public LocalWordlistSafetyService(SafetyProperties properties, ResourceLoader resourceLoader) {
        this(properties, resourceLoader, BUILTIN_WORDLIST);
    }

    /**
     * 测试友好构造：可指定内置回退词库路径（用于验证 Fail-Closed 路径）。
     *
     * @param properties           安全配置
     * @param resourceLoader       资源加载器
     * @param builtinWordlistPath  内置回退词库路径
     */
    public LocalWordlistSafetyService(SafetyProperties properties, ResourceLoader resourceLoader,
                                      String builtinWordlistPath) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
        LoadResult result = loadWordlist(properties.wordlistPath());
        if (!result.loaded()) {
            result = loadWordlist(builtinWordlistPath);
        }
        this.words = result.words();
        this.loaded = result.loaded();
        if (loaded) {
            log.info("内容安全词库加载完成，词条数={}", words.size());
        } else {
            log.error("内容安全词库不可用，将按 Fail-Closed 策略处理（failClosed={}）", properties.failClosed());
        }
    }

    @Override
    public SafetyVerdict review(String text) {
        if (!loaded) {
            return failClosedOrPass();
        }
        if (text == null || text.isBlank()) {
            return SafetyVerdict.pass();
        }
        for (String word : words) {
            if (!word.isBlank() && text.contains(word)) {
                log.warn("内容安全命中敏感词（不记录原文，BR-30）");
                return SafetyVerdict.hit(word);
            }
        }
        return SafetyVerdict.pass();
    }

    private SafetyVerdict failClosedOrPass() {
        // BR-12：默认 Fail-Closed；仅显式关闭时才放行
        return properties.failClosed() ? SafetyVerdict.unavailable() : SafetyVerdict.pass();
    }

    private LoadResult loadWordlist(String path) {
        if (path == null || path.isBlank()) {
            return LoadResult.missing();
        }
        try {
            Resource resource = path.startsWith("classpath:")
                    ? new ClassPathResource(path.substring("classpath:".length()))
                    : resourceLoader.getResource(path);
            if (!resource.exists()) {
                return LoadResult.missing();
            }
            Set<String> loaded = new LinkedHashSet<>();
            try (InputStream in = resource.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    loaded.add(trimmed);
                }
            }
            return LoadResult.of(loaded);
        } catch (IOException | RuntimeException e) {
            log.warn("加载词库失败: path={} err={}", path, e.getMessage());
            return LoadResult.missing();
        }
    }

    /**
     * 词库加载结果。
     *
     * @param loaded 是否成功加载
     * @param words  词条集合
     */
    private record LoadResult(boolean loaded, Set<String> words) {

        static LoadResult of(Set<String> words) {
            return new LoadResult(true, Collections.unmodifiableSet(words));
        }

        static LoadResult missing() {
            return new LoadResult(false, Set.of());
        }
    }
}
