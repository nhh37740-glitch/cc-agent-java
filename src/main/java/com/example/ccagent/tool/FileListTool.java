package com.example.ccagent.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.example.ccagent.service.PathValidator;

/**
 * 目录列表工具。
 *
 * 这个工具只列目录，不读取文件内容。
 */
@Component
public class FileListTool implements AgentTool {

    @Autowired
    private PathValidator pathValidator;

    @Override
    public String getName() {
        return "file_list";
    }

    @Override
    public String getDescription() {
        return "列出指定目录的文件和子目录";
    }

    @Override
    public String execute(Map<String, Object> input) throws Exception {
        String rawPath = readPath(input);
        String safePath = pathValidator.validate(rawPath);
        Path directory = Path.of(safePath);

        if (!Files.exists(directory)) {
            throw new IllegalArgumentException("目录不存在: " + rawPath);
        }
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("这不是目录，请使用 file_read 读取文件: " + rawPath);
        }

        try (Stream<Path> stream = Files.list(directory)) {
            List<String> lines = stream
                .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                .map(this::formatEntry)
                .toList();

            if (lines.isEmpty()) {
                return "(空目录)";
            }
            return String.join(System.lineSeparator(), lines);
        }
    }

    private String readPath(Map<String, Object> input) {
        Object value = input.getOrDefault("path", input.get("file_path"));
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        return value.toString();
    }

    private String formatEntry(Path path) {
        String prefix = Files.isDirectory(path) ? "[dir] " : "[file] ";
        return prefix + path.getFileName();
    }
}
