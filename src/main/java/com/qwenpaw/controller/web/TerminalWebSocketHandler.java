package com.qwenpaw.controller.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwenpaw.controller.config.QwenPawProperties;
import com.qwenpaw.controller.service.KubernetesService;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把浏览器 WebSocket 终端和 Kubernetes Pod exec 会话桥接起来。
 */
@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    /**
     * 终端连接和异常日志。
     */
    private static final Logger log = LoggerFactory.getLogger(TerminalWebSocketHandler.class);

    /**
     * 打开交互式终端时进入的业务容器名称。
     */
    private static final String CONTAINER_NAME = "qwenpaw";

    /**
     * 用于发起 Kubernetes exec 会话的客户端。
     */
    private final KubernetesClient client;

    /**
     * 用于按用户标签查找 Running 状态 Pod 的服务。
     */
    private final KubernetesService kubernetesService;

    /**
     * qwenpaw.* 配置项。
     */
    private final QwenPawProperties properties;

    /**
     * 解析前端发送的终端输入和窗口 resize 消息。
     */
    private final ObjectMapper objectMapper;

    /**
     * WebSocket sessionId 到 Kubernetes exec 会话的映射。
     */
    private final Map<String, TerminalSession> sessions = new ConcurrentHashMap<>();

    /**
     * 注入 Kubernetes 客户端、业务服务、配置和 JSON 解析器。
     */
    public TerminalWebSocketHandler(KubernetesClient client,
                                    KubernetesService kubernetesService,
                                    QwenPawProperties properties,
                                    ObjectMapper objectMapper) {
        this.client = client;
        this.kubernetesService = kubernetesService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * WebSocket 连接建立后，根据 user_id 找 Pod 并打开 /bin/bash exec 会话。
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId = normalizeUserId(queryParam(session.getUri(), "user_id"));
        if (userId == null) {
            closeWithMessage(session, "缺少 user_id 参数\r\n", CloseStatus.BAD_DATA);
            return;
        }

        // 终端只连接 Running 状态的 Pod，避免连到正在创建或失败的实例。
        Optional<Pod> pod = findRunningUserPod(userId);
        if (pod.isEmpty()) {
            closeWithMessage(session, "没有找到 Running 状态的 Pod: " + userId + "\r\n", CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        String podName = pod.get().getMetadata().getName();
        // output 把 Pod exec 输出流写回浏览器 WebSocket。
        WebSocketOutputStream output = new WebSocketOutputStream(session);
        ExecWatch execWatch;
        try {
            // redirectingInput 让浏览器输入可以写入 exec stdin，withTTY 开启交互终端模式。
            execWatch = client.pods()
                    .inNamespace(properties.getK8sNamespace())
                    .withName(podName)
                    .inContainer(CONTAINER_NAME)
                    .redirectingInput()
                    .writingOutput(output)
                    .writingError(output)
                    .withTTY()
                    .usingListener(new TerminalExecListener(session))
                    .exec("/bin/bash");
        } catch (KubernetesClientException e) {
            log.warn("Failed to open terminal for user {} pod {}", userId, podName, e);
            closeWithMessage(session, "打开终端失败: " + e.getMessage() + "\r\n", CloseStatus.SERVER_ERROR);
            return;
        }

        sessions.put(session.getId(), new TerminalSession(execWatch));
        sendText(session, "Connected to " + podName + " / " + CONTAINER_NAME + "\r\n");
    }

    /**
     * 处理浏览器发来的终端输入或窗口大小调整消息。
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        TerminalSession terminal = sessions.get(session.getId());
        if (terminal == null) {
            return;
        }
        TerminalClientMessage clientMessage = parseClientMessage(message.getPayload());
        // resize 消息只调整 TTY 大小，不写入命令行输入。
        if ("resize".equals(clientMessage.type())) {
            resizeTerminal(terminal, clientMessage);
            return;
        }
        // inputData 是用户在浏览器终端里键入的原始字符。
        String inputData = clientMessage.data();
        if (inputData == null || inputData.isEmpty()) {
            return;
        }
        writeToTerminalInput(terminal, inputData);
    }

    /**
     * 解析前端消息；兼容旧客户端直接发送纯文本输入。
     */
    private TerminalClientMessage parseClientMessage(String payload) {
        try {
            TerminalClientMessage message = objectMapper.readValue(payload, TerminalClientMessage.class);
            if (message.type() != null) {
                return message;
            }
        } catch (JsonProcessingException ignored) {
            // Older clients send raw terminal input. Keep that path working.
        }
        return new TerminalClientMessage("input", payload, null, null);
    }

    /**
     * 调整 Kubernetes exec TTY 的列数和行数。
     */
    private void resizeTerminal(TerminalSession terminal, TerminalClientMessage message) {
        Integer cols = message.cols();
        Integer rows = message.rows();
        if (cols == null || rows == null || cols < 20 || rows < 5) {
            return;
        }
        terminal.execWatch().resize(cols, rows);
    }

    /**
     * 把浏览器终端输入写入 Kubernetes exec stdin。
     */
    private void writeToTerminalInput(TerminalSession terminal, String inputData) throws IOException {
        OutputStream input = terminal.execWatch().getInput();
        if (input == null) {
            return;
        }
        input.write(inputData.getBytes(StandardCharsets.UTF_8));
        input.flush();
    }

    /**
     * WebSocket 正常关闭时释放对应的 Kubernetes exec 会话。
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        closeExec(session.getId());
    }

    /**
     * WebSocket 传输异常时释放对应的 Kubernetes exec 会话。
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Terminal websocket transport error for session {}", session.getId(), exception);
        closeExec(session.getId());
    }

    /**
     * 按用户标签查找可连接的 Running Pod。
     */
    private Optional<Pod> findRunningUserPod(String userId) {
        List<Pod> pods = kubernetesService.listPodsByLabel(Map.of(
                "app", properties.getQwenpawAppLabel(),
                "user", userId));
        return pods.stream()
                .filter(pod -> pod.getStatus() != null)
                .filter(pod -> "Running".equals(pod.getStatus().getPhase()))
                .findFirst();
    }

    /**
     * 从 WebSocket URI 中读取指定查询参数。
     */
    private String queryParam(URI uri, String name) {
        if (uri == null) {
            return null;
        }
        return UriComponentsBuilder.fromUri(uri)
                .build()
                .getQueryParams()
                .getFirst(name);
    }

    /**
     * 规范化终端连接使用的用户 ID。
     */
    private String normalizeUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        return userId.toLowerCase(Locale.ROOT);
    }

    /**
     * 向浏览器发送提示消息后关闭 WebSocket。
     */
    private void closeWithMessage(WebSocketSession session, String message, CloseStatus status) {
        sendText(session, message);
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("Failed to close terminal websocket session {}", session.getId(), e);
        }
    }

    /**
     * 关闭并移除指定 WebSocket session 对应的 Kubernetes exec 会话。
     */
    private void closeExec(String sessionId) {
        TerminalSession terminal = sessions.remove(sessionId);
        if (terminal != null) {
            terminal.execWatch().close();
        }
    }

    /**
     * 线程安全地向浏览器 WebSocket 写文本。
     */
    private void sendText(WebSocketSession session, String text) {
        if (!session.isOpen()) {
            return;
        }
        // Spring WebSocketSession 不是多线程并发发送安全的，输出流回调和业务线程要串行化。
        synchronized (session) {
            try {
                session.sendMessage(new TextMessage(text));
            } catch (IOException e) {
                log.debug("Failed to send terminal output for session {}", session.getId(), e);
            }
        }
    }

    /**
     * 保存一个浏览器终端连接对应的 Kubernetes exec 句柄。
     */
    private record TerminalSession(ExecWatch execWatch) {
    }

    /**
     * 前端发来的终端消息，type=input 表示输入，type=resize 表示调整窗口。
     */
    private record TerminalClientMessage(String type, String data, Integer cols, Integer rows) {
    }

    /**
     * 监听 Kubernetes exec 会话关闭或失败事件。
     */
    private class TerminalExecListener implements ExecListener {

        /**
         * 当前 exec 会话绑定的浏览器 WebSocket。
         */
        private final WebSocketSession session;

        /**
         * 绑定浏览器 WebSocket session。
         */
        TerminalExecListener(WebSocketSession session) {
            this.session = session;
        }

        /**
         * Kubernetes exec 正常关闭时通知浏览器并释放本地会话。
         */
        @Override
        public void onClose(int code, String reason) {
            sendText(session, "\r\nTerminal closed: " + reason + "\r\n");
            closeExec(session.getId());
        }

        /**
         * Kubernetes exec 失败时通知浏览器并释放本地会话。
         */
        @Override
        public void onFailure(Throwable throwable, Response failureResponse) {
            String message = throwable == null ? "unknown error" : throwable.getMessage();
            sendText(session, "\r\nTerminal error: " + message + "\r\n");
            closeExec(session.getId());
        }
    }

    /**
     * 把 Kubernetes exec 输出流转换成 WebSocket 文本消息。
     */
    private class WebSocketOutputStream extends OutputStream {

        /**
         * 接收终端输出的浏览器 WebSocket。
         */
        private final WebSocketSession session;

        /**
         * 绑定浏览器 WebSocket session。
         */
        WebSocketOutputStream(WebSocketSession session) {
            this.session = session;
        }

        /**
         * 写入单字节输出。
         */
        @Override
        public void write(int b) {
            write(new byte[]{(byte) b}, 0, 1);
        }

        /**
         * 把 Pod 输出的字节块按 UTF-8 转为文本发给浏览器。
         */
        @Override
        public void write(byte[] b, int off, int len) {
            if (len <= 0) {
                return;
            }
            sendText(session, new String(b, off, len, StandardCharsets.UTF_8));
        }
    }
}
