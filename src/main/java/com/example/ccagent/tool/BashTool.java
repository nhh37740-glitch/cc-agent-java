// package = 声明这个文件属于哪个包
package com.example.ccagent.tool;

// import = 引入其他包的类
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

// import = 引入 Spring 的组件标记和依赖注入
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// import = 引入项目内部的类
import com.example.ccagent.config.AgentProperties;

/**
 * 命令执行工具。
 *
 * 这个工具只执行白名单命令，并且工作目录固定在 workspace。
 */
@Component
public class BashTool implements AgentTool {

    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_CHARS = 20000;

    private static final Set<String> ALLOWED_COMMANDS = Set.of(
        "git",
        "rg",
        "gradlew",
        "gradlew.bat",
        "mkdir",
        "touch",
        "rm",
        "del",
        "rmdir",
        "mv",
        "move",
        "cp",
        "copy"
    );

    private static final Set<String> ALLOWED_GIT_SUBCOMMANDS = Set.of(
        "status",
        "diff",
        "log",
        "show",
        "branch",
        "rev-parse",
        "ls-files",
        "grep"
    );

    private static final Set<String> ALLOWED_GRADLE_TASKS = Set.of(
        "build",
        "clean",
        "test",
        "classes",
        "compileJava",
        "processResources",
        "bootJar"
    );

    private static final Set<String> ALLOWED_GRADLE_OPTIONS = Set.of(
        "--stacktrace",
        "--info",
        "--debug",
        "--no-daemon",
        "-q"
    );

    @Autowired
    private AgentProperties properties;

    @Override
    public String getName() {
        return "bash";
    }

    @Override
    public String getDescription() {
        return "在 workspace 内执行白名单命令";
    }

    /**
     * 执行命令。
     *
     * @param input 参数集合，需要包含 command
     * @return 命令输出
     * @throws Exception 命令不在白名单、超时或执行失败时抛出异常
     */
    @Override
    public String execute(Map<String, Object> input) throws Exception {
        String command = readCommand(input);
        rejectShellControlChars(command);

        List<String> parts = splitCommand(command);
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("command 不能为空");
        }

        String executable = normalizeExecutable(parts.get(0));
        validateCommand(executable, parts);

        if (isFileOperation(executable)) {
            return executeFileOperation(executable, parts);
        }

        List<String> processCommand = new ArrayList<>(parts);

        Path workspaceDir = workspaceDir();
        processCommand.set(0, resolveExecutable(executable, workspaceDir));
        ProcessBuilder builder = new ProcessBuilder(processCommand);
        builder.directory(workspaceDir.toFile());
        builder.redirectErrorStream(true);

        Process process = builder.start();
        CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readOutput(process.getInputStream()));

        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("命令执行超时，已终止: " + command);
        }

        String output = outputFuture.get(5, TimeUnit.SECONDS);
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException("命令执行失败 (退出码 " + exitCode + "): " + limitOutput(output));
        }

        return limitOutput(output);
    }

    // Gradle Wrapper 属于当前 workspace，不能依赖它恰好位于系统 PATH。
    private String resolveExecutable(String executable, Path workspaceDir) {
        if (!"gradlew".equals(executable) && !"gradlew.bat".equals(executable)) {
            return executable;
        }

        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        if (!windows && "gradlew.bat".equals(executable)) {
            throw new IllegalArgumentException("Linux 容器内请使用 gradlew，不能执行 gradlew.bat");
        }

        Path wrapper = workspaceDir.resolve(executable).normalize();
        if (!wrapper.startsWith(workspaceDir) || !Files.isRegularFile(wrapper)) {
            throw new IllegalArgumentException("workspace 内没有 Gradle Wrapper: " + executable);
        }
        if (!windows && !Files.isExecutable(wrapper)) {
            throw new IllegalArgumentException("Gradle Wrapper 没有执行权限: " + executable);
        }
        return wrapper.toString();
    }

    private String readCommand(Map<String, Object> input) {
        Object value = input.get("command");
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("command 不能为空");
        }
        return value.toString().trim();
    }

    private void rejectShellControlChars(String command) {
        if (command.contains("\n") || command.contains("\r")) {
            throw new IllegalArgumentException("command 不能包含换行");
        }
        for (String token : List.of(";", "&&", "||", "|", ">", "<", "`", "$(")) {
            if (command.contains(token)) {
                throw new IllegalArgumentException("command 包含不允许的 shell 控制符: " + token);
            }
        }
    }

    private List<String> splitCommand(String command) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
            if (Character.isWhitespace(c) && !inSingleQuote && !inDoubleQuote) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }

        if (inSingleQuote || inDoubleQuote) {
            throw new IllegalArgumentException("command 引号没有闭合");
        }
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }

    private String normalizeExecutable(String rawExecutable) {
        String executable = rawExecutable.replace('/', '\\');
        int slashIndex = executable.lastIndexOf('\\');
        if (slashIndex >= 0) {
            executable = executable.substring(slashIndex + 1);
        }
        executable = executable.toLowerCase();

        if (!ALLOWED_COMMANDS.contains(executable)) {
            throw new SecurityException("命令不在白名单: " + rawExecutable);
        }
        return executable;
    }

    private void validateCommand(String executable, List<String> parts) {
        rejectPathEscape(parts);

        if ("git".equals(executable)) {
            validateGit(parts);
            return;
        }
        if ("gradlew".equals(executable) || "gradlew.bat".equals(executable)) {
            validateGradle(parts);
        }
    }

    private boolean isFileOperation(String executable) {
        return Set.of("mkdir", "touch", "rm", "del", "rmdir", "mv", "move", "cp", "copy")
            .contains(executable);
    }

    private String executeFileOperation(String executable, List<String> parts) throws Exception {
        return switch (executable) {
            case "mkdir" -> executeMkdir(parts);
            case "touch" -> executeTouch(parts);
            case "rm", "del", "rmdir" -> executeDelete(parts);
            case "mv", "move" -> executeMove(parts);
            case "cp", "copy" -> executeCopy(parts);
            default -> throw new SecurityException("文件操作命令不在白名单: " + executable);
        };
    }

    private void validateGit(List<String> parts) {
        if (parts.size() < 2) {
            throw new SecurityException("git 必须指定子命令");
        }
        String subcommand = parts.get(1);
        if (!ALLOWED_GIT_SUBCOMMANDS.contains(subcommand)) {
            throw new SecurityException("git 子命令不在白名单: " + subcommand);
        }
    }

    private void validateGradle(List<String> parts) {
        if (parts.size() < 2) {
            throw new SecurityException("gradlew 必须指定任务名");
        }

        for (int i = 1; i < parts.size(); i++) {
            String arg = parts.get(i);
            if (arg.startsWith("-")) {
                if (!ALLOWED_GRADLE_OPTIONS.contains(arg)) {
                    throw new SecurityException("gradlew 参数不在白名单: " + arg);
                }
                continue;
            }
            if (!ALLOWED_GRADLE_TASKS.contains(arg)) {
                throw new SecurityException("gradlew 任务不在白名单: " + arg);
            }
        }
    }

    private void rejectPathEscape(List<String> parts) {
        for (String part : parts) {
            String normalized = part.replace('\\', '/');
            if (normalized.contains("../") || normalized.equals("..") || normalized.startsWith("/")) {
                throw new SecurityException("参数不能指向 workspace 外部: " + part);
            }
            if (part.length() >= 2 && Character.isLetter(part.charAt(0)) && part.charAt(1) == ':') {
                throw new SecurityException("参数不能使用绝对路径: " + part);
            }
        }
    }

    private Path workspaceDir() throws Exception {
        String workspaceDir = properties.workspaceDir() == null || properties.workspaceDir().isBlank()
            ? "./workspace"
            : properties.workspaceDir();
        Path path = Path.of(workspaceDir).toAbsolutePath().normalize();
        Files.createDirectories(path);
        return path;
    }

    private String executeMkdir(List<String> parts) throws Exception {
        if (parts.size() < 2) {
            throw new IllegalArgumentException("mkdir 需要目录路径");
        }
        List<String> created = new ArrayList<>();
        for (int i = 1; i < parts.size(); i++) {
            Path path = resolveWorkspacePath(parts.get(i));
            Files.createDirectories(path);
            created.add(relativeToWorkspace(path));
        }
        return "已创建目录:\n" + String.join("\n", created);
    }

    private String executeTouch(List<String> parts) throws Exception {
        if (parts.size() < 2) {
            throw new IllegalArgumentException("touch 需要文件路径");
        }
        List<String> touched = new ArrayList<>();
        for (int i = 1; i < parts.size(); i++) {
            Path path = resolveWorkspacePath(parts.get(i));
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(path)) {
                Files.createFile(path);
            }
            touched.add(relativeToWorkspace(path));
        }
        return "已创建或更新时间戳:\n" + String.join("\n", touched);
    }

    private String executeDelete(List<String> parts) throws Exception {
        if (parts.size() < 2) {
            throw new IllegalArgumentException(parts.get(0) + " 需要删除路径");
        }

        boolean recursive = false;
        List<String> targets = new ArrayList<>();
        for (int i = 1; i < parts.size(); i++) {
            String arg = parts.get(i);
            if ("-r".equals(arg) || "-rf".equals(arg) || "-fr".equals(arg)) {
                recursive = true;
            } else {
                targets.add(arg);
            }
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException(parts.get(0) + " 需要删除路径");
        }

        List<String> deleted = new ArrayList<>();
        for (String target : targets) {
            Path path = resolveWorkspacePath(target);
            rejectWorkspaceRoot(path);
            if (!Files.exists(path)) {
                continue;
            }
            if (Files.isDirectory(path)) {
                if (!recursive && !isDirectoryEmpty(path)) {
                    throw new IllegalArgumentException("目录非空，删除目录请加 -r: " + target);
                }
                deleteRecursively(path);
            } else {
                Files.delete(path);
            }
            deleted.add(relativeToWorkspace(path));
        }
        return deleted.isEmpty() ? "没有匹配到需要删除的路径" : "已删除:\n" + String.join("\n", deleted);
    }

    private String executeMove(List<String> parts) throws Exception {
        if (parts.size() != 3) {
            throw new IllegalArgumentException(parts.get(0) + " 需要源路径和目标路径");
        }
        Path source = resolveWorkspacePath(parts.get(1));
        Path target = resolveWorkspacePath(parts.get(2));
        rejectWorkspaceRoot(source);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        return "已移动: " + relativeToWorkspace(source) + " -> " + relativeToWorkspace(target);
    }

    private String executeCopy(List<String> parts) throws Exception {
        if (parts.size() != 3) {
            throw new IllegalArgumentException(parts.get(0) + " 需要源路径和目标路径");
        }
        Path source = resolveWorkspacePath(parts.get(1));
        Path target = resolveWorkspacePath(parts.get(2));
        rejectWorkspaceRoot(source);
        if (Files.isDirectory(source)) {
            throw new IllegalArgumentException("cp/copy 当前只复制文件，不复制目录: " + parts.get(1));
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        return "已复制: " + relativeToWorkspace(source) + " -> " + relativeToWorkspace(target);
    }

    private Path resolveWorkspacePath(String rawPath) throws Exception {
        Path workspace = workspaceDir();
        Path resolved = workspace.resolve(rawPath).normalize();
        if (!resolved.startsWith(workspace)) {
            throw new SecurityException("路径超出 workspace: " + rawPath);
        }
        return resolved;
    }

    private void rejectWorkspaceRoot(Path path) throws Exception {
        if (path.equals(workspaceDir())) {
            throw new SecurityException("不允许操作 workspace 根目录");
        }
    }

    private boolean isDirectoryEmpty(Path path) throws Exception {
        try (var stream = Files.list(path)) {
            return stream.findAny().isEmpty();
        }
    }

    private void deleteRecursively(Path path) throws Exception {
        try (var stream = Files.walk(path)) {
            List<Path> paths = stream
                .sorted((left, right) -> right.compareTo(left))
                .toList();
            for (Path current : paths) {
                Files.deleteIfExists(current);
            }
        }
    }

    private String relativeToWorkspace(Path path) throws Exception {
        return workspaceDir().relativize(path).toString();
    }

    private String readOutput(InputStream inputStream) {
        try {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("读取命令输出失败: " + e.getMessage(), e);
        }
    }

    private String limitOutput(String output) {
        if (output == null || output.length() <= MAX_OUTPUT_CHARS) {
            return output == null ? "" : output;
        }
        return output.substring(0, MAX_OUTPUT_CHARS)
            + "\n\n[输出过长，已截断到 " + MAX_OUTPUT_CHARS + " 字符]";
    }
}
