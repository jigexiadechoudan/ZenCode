package dev.zencode.permission.rule;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 规则引擎：按三层优先级加载规则并做 glob 匹配。
 * <p>
 * 优先级 SESSION（内存）> PROJECT（项目级文件）> USER（用户级文件）。
 * 同一层按声明顺序首个命中生效；高优先级层命中后不再匹配低优先级层。
 * glob 模式大小写不敏感；* 匹配任意字符序列，? 匹配单字符，其余按字面量。
 * </p>
 * <p>
 * 规则文件不存在时视为空规则。会话级规则仅存内存；项目级规则可持久化写回。
 * </p>
 */
public final class RuleEngine {
    private final List<Rule> sessionRules = new ArrayList<>();
    private final List<Rule> projectRules = new ArrayList<>();
    private final List<Rule> userRules = new ArrayList<>();
    private Path projectRulesPath;
    private final Path userRulesPath;

    /**
     * @param projectRulesPath 项目级规则文件路径（不存在时视为空）
     * @param userRulesPath 用户级规则文件路径（不存在时视为空）
     */
    public RuleEngine(Path projectRulesPath, Path userRulesPath) {
        this.projectRulesPath = projectRulesPath;
        this.userRulesPath = userRulesPath;
        load(projectRules, projectRulesPath);
        load(userRules, userRulesPath);
    }

    public void refreshProject(Path rulesPath) {
        projectRulesPath = rulesPath;
        sessionRules.clear();
        projectRules.clear();
        load(projectRules, rulesPath);
    }

    /** 加载规则文件到目标列表；文件不存在或格式损坏时保留空列表。 */
    private static void load(List<Rule> target, Path path) {
        if (path == null || !Files.exists(path)) return;
        try {
            Object parsed = new Load(LoadSettings.builder().build()).loadFromString(Files.readString(path));
            if (!(parsed instanceof Map<?, ?> root)) return;
            Object rules = root.get("rules");
            if (!(rules instanceof List<?> list)) return;
            for (Object raw : list) {
                if (!(raw instanceof Map<?, ?> item)) continue;
                Object tool = item.get("tool");
                Object pattern = item.get("pattern");
                Object action = item.get("action");
                if (!(pattern instanceof String p) || !(action instanceof String a)) continue;
                RuleAction ruleAction;
                try {
                    ruleAction = RuleAction.valueOf(a.trim().toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                target.add(new Rule(tool instanceof String t ? t : null, p, ruleAction));
            }
        } catch (IOException | RuntimeException e) {
            // 规则文件加载失败不阻塞启动，视为空规则
        }
    }

    /** 添加会话级规则（最高优先级，仅内存）。 */
    public void addSessionRule(Rule rule) {
        if (rule != null) sessionRules.add(rule);
    }

    /** 当前会话级规则数量。 */
    public int sessionRuleCount() {
        return sessionRules.size();
    }

    /**
     * 按工具名与核心参数值做三层匹配。
     *
     * @return 首个命中规则的动作；三层均未命中时返回空
     */
    public Optional<RuleAction> match(String toolName, String argValue) {
        return match(toolName, argValue, null);
    }

    /** 自动生成的精确规则按完整规范化参数指纹匹配；手写 glob 仍匹配核心参数。 */
    public Optional<RuleAction> match(String toolName, String argValue, String argumentKey) {
        if (toolName == null || (argValue == null && argumentKey == null)) return Optional.empty();
        if (argValue == null) argValue = "";
        Optional<RuleAction> action = matchIn(sessionRules, toolName, argValue, argumentKey);
        if (action.isPresent()) return action;
        action = matchIn(projectRules, toolName, argValue, argumentKey);
        if (action.isPresent()) return action;
        return matchIn(userRules, toolName, argValue, argumentKey);
    }

    private static Optional<RuleAction> matchIn(List<Rule> rules, String toolName,
                                                 String argValue, String argumentKey) {
        for (Rule rule : rules) {
            if (!rule.matchesAllTools() && !rule.tool().equalsIgnoreCase(toolName)) continue;
            boolean matched = rule.pattern().startsWith("sha256:")
                    ? rule.pattern().equals(argumentKey)
                    : globToRegex(rule.pattern()).matcher(argValue).matches();
            if (matched) {
                return Optional.of(rule.action());
            }
        }
        return Optional.empty();
    }

    /**
     * 将 glob 模式转换为大小写不敏感正则。
     * * 匹配任意字符序列；? 匹配单字符；其余字符按字面量转义。
     */
    private static Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char ch = glob.charAt(i);
            switch (ch) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append(".");
                default -> {
                    if ("\\.[]{}()+-^$|".indexOf(ch) >= 0) regex.append('\\');
                    regex.append(ch);
                }
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    /**
     * 将规则持久化写入项目级规则文件（追加）。
     *
     * @return 写入成功返回 true；文件不可写或路径为空返回 false
     */
    public boolean persistProjectRule(Rule rule) {
        if (rule == null || projectRulesPath == null) return false;
        try {
            List<Rule> existing = new ArrayList<>(projectRules);
            existing.add(rule);
            Path parent = projectRulesPath.getParent();
            if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);

            Map<String, Object> root = new LinkedHashMap<>();
            List<Map<String, Object>> ruleList = new ArrayList<>();
            for (Rule r : existing) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("tool", r.matchesAllTools() ? "*" : r.tool());
                item.put("pattern", r.pattern());
                item.put("action", r.action().name().toLowerCase(java.util.Locale.ROOT));
                ruleList.add(item);
            }
            root.put("rules", ruleList);
            String yaml = new Dump(DumpSettings.builder().build()).dumpToString(root);
            Files.writeString(projectRulesPath, yaml);
            // 同步内存中的项目规则
            projectRules.clear();
            projectRules.addAll(existing);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
