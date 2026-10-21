package dev.zencode.mcp.testutil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * 测试桩：从 stdin 逐行读取并回显到 stdout（UTF-8），用于 stdio 传输测试。
 */
public final class EchoMain {
    private EchoMain() {}

    public static void main(String[] args) throws IOException {
        BufferedReader br = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        String line;
        while ((line = br.readLine()) != null) {
            out.println(line);
        }
    }
}
