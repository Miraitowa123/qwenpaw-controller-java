# QwenPaw Controller (Spring Boot)

QwenPaw Controller 是一个基于 Java 17 + Spring Boot 的 Kubernetes 用户 Pod 管理控制器，为每个用户动态创建、管理和暴露独立的 QwenPaw 实例。

## 功能特性

- 动态创建用户专属 `Deployment`、`Service`、`HTTPRoute`
- 使用 `qwenpaw-{user_id}` 作为用户资源名前缀
- 使用 `app=qwenpaw,user={user_id}` label 查询和恢复用户 Pod 状态
- 支持 Gateway API `HTTPRoute` 路由和 URLRewrite
- 支持查看 Pod 日志、同步状态、清理孤立 Service/HTTPRoute
- 支持通过 Web UI/API 查看和更新 Controller ConfigMap，并可触发 Controller 滚动重启

## 技术栈

- JDK 17
- Gradle 8.10
- Spring Boot 3.2.12
- Fabric8 Kubernetes Client

> 说明：Spring Boot 3.2.x 与 Gradle 8.10/JDK 17 组合稳定兼容。

## API

所有接口默认挂在 `/bocompawAdmin` context-path 下。

| 方法 | 路径 | 描述 |
|------|------|------|
| POST | `/bocompawAdmin/api/v1/users/{user_id}/pod` | 获取或创建用户 Pod |
| GET | `/bocompawAdmin/api/v1/users/{user_id}/pod` | 查询用户 Pod 状态 |
| POST | `/bocompawAdmin/api/v1/users/{user_id}/pod/restart` | 重启用户 Pod |
| DELETE | `/bocompawAdmin/api/v1/users/{user_id}/pod` | 删除用户 Pod |
| GET | `/bocompawAdmin/api/v1/users/{user_id}/logs` | 获取用户 Pod 日志 |
| GET | `/bocompawAdmin/api/v1/users/pods` | 列出所有用户 Pod |
| GET | `/bocompawAdmin/api/v1/skills/{skill_name}/download` | 下载当前用户指定技能目录 zip |
| POST | `/bocompawAdmin/api/v1/admin/sync` | 同步用户 Pod 状态 |
| POST | `/bocompawAdmin/api/v1/admin/cleanup` | 清理孤立资源 |
| GET | `/bocompawAdmin/api/v1/admin/personal-api-keys` | 获取当前用户的 personal-api-key.json 和 api-key |
| GET | `/bocompawAdmin/api/v1/admin/config` | 获取 Controller ConfigMap |
| PUT | `/bocompawAdmin/api/v1/admin/config` | 更新 Controller ConfigMap |
| GET | `/bocompawAdmin/api/v1/admin/runtime-config` | 获取 QwenPaw 运行时变量 ConfigMap |
| PUT | `/bocompawAdmin/api/v1/admin/runtime-config` | 更新运行时变量并刷新所有 QwenPaw Pod |
| WS | `/bocompawAdmin/api/v1/terminal?user_id={user_id}` | 打开指定用户 Pod 的终端 |
| WS | `/bocompawAdmin/api/v1/terminal?target=controller` | 打开 controller Pod 的管理终端 |
| GET | `/bocompawAdmin/health` | 健康检查 |
| GET | `/bocompawAdmin/ready` | 就绪检查 |

## 构建和运行

本地使用指定 Gradle：

```bash
/Users/roxy/Documents/05DevTools/gradle-8.10/bin/gradle clean bootJar
```

运行：

```bash
java -jar build/libs/qwenpaw-controller-java-0.1.0.jar
```

构建镜像：

```bash
docker build -t qwenpaw-controller-java:0.1.0 .
```

部署：

```bash
kubectl apply -f deployments/k8s.yaml
```

## 配置

控制器通过环境变量配置，主要配置如下：

| 环境变量 | 默认值                                   | 说明 |
|----------|---------------------------------------|------|
| SERVER_HOST | `0.0.0.0`                             | 服务监听地址 |
| SERVER_PORT | `8080`                                | 服务端口 |
| SERVER_LOG_LEVEL | `info`                                | 日志级别 |
| K8S_NAMESPACE | `ai`                                  | Kubernetes 命名空间 |
| QWENPAW_APP_LABEL | `qwenpaw`                             | 用户 Pod app label |
| QWENPAW_IMAGE | `docker.io/agentscope/qwenpaw:latest` | QwenPaw 镜像 |
| QWENPAW_CONTAINER_PORT | `8088`                                | QwenPaw 容器端口 |
| QWENPAW_CONFIGMAP_NAME | `qwenpaw-global-config`               | QwenPaw 配置 ConfigMap |
| QWENPAW_RUNTIME_CONFIGMAP_NAME | `qwenpaw-runtime-config`              | QwenPaw 运行时变量 ConfigMap，通过 envFrom 注入用户 Pod |
| QWENPAW_NAS_PVC_NAME | `qwenpaw-nas-pvc`                     | 用户数据 PVC |
| QWENPAW_PUBLIC_TEMPLATE_SUB_PATH | `public-secret`                       | 用户 Pod 初始化模板目录，位于用户数据 PVC 内 |
| QWENPAW_VOLUME_MODE | `subpath`                             | PVC 挂载模式；`subpath` 隔离用户目录，`single-mount` 用于 Docker Desktop 本地调试 |
| QWENPAW_NAS_MOUNT_PATH | `/qwenpaw_nas`                        | `single-mount` 模式下容器内 NAS 根目录 |
| GATEWAY_NAME | `traefik-gateway`                     | Gateway 名称 |
| GATEWAY_NAMESPACE | `ai`                                  | Gateway 命名空间 |
| BASE_PATH_PREFIX | `/users`                              | 用户访问路径前缀 |
| CONTROLLER_CONFIGMAP_NAME | `qwenpaw-controller-config`           | Controller ConfigMap |
| CONTROLLER_DEPLOYMENT_NAME | `qwenpaw-controller`                  | Controller Deployment |
| POD_READY_TIMEOUT | `600`                                 | 等待用户 Pod Ready 的超时时间，单位秒 |
| STARTUP_SYNC_ENABLED | `false`                               | 启动时是否立即同步 Kubernetes Pod；集群部署清单中设为 `true` |

## 项目结构

```text
.
├── build.gradle
├── settings.gradle
├── Dockerfile
├── deployments/
│   └── k8s.yaml
└── src/
    └── main/
        ├── java/com/qwenpaw/controller/
        │   ├── QwenPawControllerApplication.java
        │   ├── config/
        │   ├── model/
        │   ├── service/
        │   └── web/
        └── resources/
            ├── application.yml
            └── static/index.html
```

## 使用示例

```bash
curl -X POST http://controller:8080/bocompawAdmin/api/v1/users/alice/pod
```

下载技能：

```bash
curl -O -J "http://controller:8080/bocompawAdmin/api/v1/skills/%E6%8A%80%E8%83%BD1/download" \
  -H "Cookie: userid=alice"
```

查看当前用户 personal-api-key：

```bash
curl "http://controller:8080/bocompawAdmin/api/v1/admin/personal-api-keys" \
  -H "Cookie: userid=alice"
```

返回示例：

```json
{
  "user_id": "alice",
  "pod_name": "qwenpaw-alice-7f9d6b8c9d-2x7k8",
  "deployment_name": "qwenpaw-alice",
  "service_name": "qwenpaw-alice",
  "httproute_name": "qwenpaw-alice",
  "path_prefix": "/users/alice",
  "status": "running",
  "access_url": "/users/alice",
  "message": "Pod is ready"
}
```

Web 管理界面访问：

```text
http://controller:8080/bocompawAdmin
```
