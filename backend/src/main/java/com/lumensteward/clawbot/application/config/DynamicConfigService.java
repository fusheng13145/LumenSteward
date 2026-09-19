package com.lumensteward.clawbot.application.config;

import java.util.List;
import java.util.Set;

/**
 * 运行时动态配置源（FR-18 / 迭代 2 T10）。
 *
 * <p>与启动期 {@code @ConfigurationProperties} 的区别：本接口的取值来自 {@code sys_config} 表
 * （经 {@code ConfigCacheService} 三级缓存），<b>管理后台改值后下一次读取即生效，无需重启</b>
 * （FR-18 AC①）。契约上所有方法<b>永不抛异常</b>——配置缺失、缓存不可用、值格式非法一律回退
 * 传入的兜底值并告警，避免"配置项误填导致整个对话链路 500"。
 *
 * <p>依赖铁律（NFR-MA-03）：接口定义在 application 层，实现落在 infrastructure 层
 * （{@code DynamicConfigServiceImpl}），application 不反向依赖具体实现。
 */
public interface DynamicConfigService {

    /**
     * 读取原始字符串值。
     *
     * @param key 配置键（见 {@link ConfigKeys}）
     * @return 配置值；不存在或不可读返回 null
     */
    String get(String key);

    /**
     * 读取字符串（带兜底）。
     *
     * @param key      配置键
     * @param fallback 兜底值（配置缺失 / 空值时使用）
     * @return 非空字符串
     */
    String getString(String key, String fallback);

    /**
     * 读取整数（带兜底）。
     *
     * @param key      配置键
     * @param fallback 兜底值（配置缺失 / 解析失败时使用）
     * @return 整数值
     */
    int getInt(String key, int fallback);

    /**
     * 读取长整数（带兜底）。
     *
     * @param key      配置键
     * @param fallback 兜底值
     * @return 长整数值
     */
    long getLong(String key, long fallback);

    /**
     * 读取布尔（带兜底）；接受 {@code true/false/1/0/yes/no}（忽略大小写）。
     *
     * @param key      配置键
     * @param fallback 兜底值
     * @return 布尔值
     */
    boolean getBoolean(String key, boolean fallback);

    /**
     * 读取列表：支持 JSON 数组（{@code ["a","b"]}）与逗号分隔（{@code a,b}）两种写法。
     *
     * @param key      配置键
     * @param fallback 兜底值（配置缺失 / 解析失败时使用）
     * @return 列表（元素已去空白，空值项被剔除）
     */
    List<String> getList(String key, List<String> fallback);

    /**
     * 读取集合（语义同 {@link #getList(String, List)}）。
     *
     * @param key      配置键
     * @param fallback 兜底值
     * @return 集合
     */
    Set<String> getSet(String key, Set<String> fallback);

    /**
     * 动态配置源是否可用（DB/缓存可读）。
     *
     * <p>不可用时所有读取走兜底值——此时行为与启动期静态配置一致，属<b>显式降级</b>而非故障。
     *
     * @return true 表示可用
     */
    boolean isAvailable();
}
