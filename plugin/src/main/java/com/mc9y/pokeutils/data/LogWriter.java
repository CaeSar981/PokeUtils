package com.mc9y.pokeutils.data;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 合成流水日志的落盘器 —— 一个「按天缓存、写后即刷」的文件句柄。
 *
 * <p><b>它替换掉了什么</b>：原来 {@code PokeUtils#logCompound} 每写一行都是
 * {@code new FileOutputStream(file, true)} + 写 + {@code close()}，而且还跑在<b>服务端主线程</b>上。
 * 这里有个尴尬的不对称：我们花了大力气用 {@code write-behind} 把 MySQL 的网络往返挪出主线程，
 * 却把文件 IO 留在了主线程 —— 每次合成都是一次 open/write/close 的系统调用。</p>
 *
 * <p><b>为什么「写后即刷」而不是攒一批</b>：流水日志是事后处理纠纷、复盘经济产出的<b>审计凭证</b>。
 * 攒批意味着服务端被 {@code taskkill /F} 强杀时会丢掉最近的一批记录 ——
 * 这个项目整条链路上都在避免这个语义（原实现只在 {@code onDisable} 落盘，就是栽在这上面）。
 * 所以这里只做一件事：<b>句柄常开，省掉每行的 open/close</b>，内容仍然立刻交给操作系统。
 * 拿到的是「少两次系统调用且不丢数据」，而不是「少几次写但可能丢数据」。</p>
 *
 * <p><b>并发</b>：调用方是 {@link Async} 的单线程写队列（保证同一条流水按发生顺序落盘），
 * 这里仍加 {@code synchronized} 兜底 —— 关服收尾时 {@code flush()} 可能来自另一条线程。</p>
 */
public final class LogWriter {

    private final Logger logger;

    /** 当前打开的文件所属的日期（{@code yyyy-MM-dd}）。 */
    private String currentDay;
    private Writer writer;

    public LogWriter(Logger logger) {
        this.logger = logger;
    }

    /**
     * 追加一行。
     *
     * @param dir  日志目录（不存在时会创建）
     * @param day  该行所属日期，用于决定文件名与是否需要换天
     * @param line 已经拼好的整行（不含换行符）
     */
    public synchronized void append(File dir, String day, String line) {
        if (line == null || day == null) {
            return;
        }
        if (this.writer != null && !day.equals(this.currentDay)) {
            // 跨天（或者在关服重启后的第一次写入）—— 换文件。
            this.closeQuietly();
        }
        if (this.writer == null && !this.open(dir, day)) {
            return;
        }
        try {
            this.writer.write(line);
            this.writer.write(System.lineSeparator());
            this.writer.flush();
        } catch (IOException ex) {
            this.logger.log(Level.WARNING, "写合成日志失败: " + ex.getMessage());
            // 句柄可能已经坏了（磁盘满 / 文件被删），关掉让下一次写入重新打开。
            this.closeQuietly();
        }
    }

    private boolean open(File dir, String day) {
        if (!dir.exists() && !dir.mkdirs()) {
            this.logger.warning("无法创建合成日志目录: " + dir.getPath());
            return false;
        }
        File file = new File(dir, "compound-" + day + ".log");
        try {
            this.writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file, true), StandardCharsets.UTF_8));
            this.currentDay = day;
            return true;
        } catch (IOException ex) {
            this.logger.log(Level.WARNING, "打开合成日志失败: " + ex.getMessage());
            this.writer = null;
            return false;
        }
    }

    /** 把缓冲刷到操作系统（关服收尾用）。 */
    public synchronized void flush() {
        if (this.writer == null) {
            return;
        }
        try {
            this.writer.flush();
        } catch (IOException ex) {
            this.logger.log(Level.WARNING, "刷新合成日志失败: " + ex.getMessage());
        }
    }

    /** 关服收尾：刷一次并关掉句柄。 */
    public synchronized void close() {
        if (this.writer == null) {
            return;
        }
        try {
            this.writer.flush();
            this.writer.close();
        } catch (IOException ex) {
            this.logger.log(Level.WARNING, "关闭合成日志失败: " + ex.getMessage());
        } finally {
            this.writer = null;
            this.currentDay = null;
        }
    }

    private void closeQuietly() {
        if (this.writer == null) {
            return;
        }
        try {
            this.writer.close();
        } catch (IOException ignored) {
            // 已经准备丢弃这个句柄了，关不上也无所谓。
        }
        this.writer = null;
        this.currentDay = null;
    }

    /** 当前是否已打开句柄（供测试观察句柄复用是否生效）。 */
    public synchronized boolean isOpen() {
        return this.writer != null;
    }
}
