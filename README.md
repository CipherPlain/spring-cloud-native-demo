# 原生 Spring Cloud 样例部署文档

> 技术栈：Spring Boot 3.2.5 + Spring Cloud 2023.0.1 + **JDK 21**
> 组件：Eureka（注册中心）+ Config（配置中心）+ OpenFeign（服务调用）+ LoadBalancer（负载均衡）+ Resilience4j（熔断）+ Gateway（网关）
> 部署：Maven 构建 + Docker 容器化（docker-compose 一键编排）

## 0. 环境准备

- **JDK 21+**（构建和本地运行都需要）
- **Maven 3.6+**
- **Docker + docker compose**（本机推荐：Docker Desktop + WSL2 集成，见第 2 节）

## 1. 工程结构

```
spring-cloud-native-demo
├── eureka-server   端口 8761  注册中心
├── config-server   端口 8888  配置中心（本地文件方式）
├── user-service    端口 8081  用户服务
├── order-service   端口 8082  订单服务（Feign 调 user-service）
├── gateway         端口 8000  网关（统一入口）
├── docker-compose.yml          一键编排 5 个服务
└── 每个模块自带 Dockerfile
```

### 1.1 各模块详解

| 模块 | 端口 | 角色 | 作用 | 关键类 / 配置 |
|------|------|------|------|----------------|
| **eureka-server** | 8761 | 注册中心 | 所有服务启动时来此注册，`user-service`、`order-service`、`gateway` 在这里登记 IP+端口；gateway 靠它做负载均衡/服务发现（`lb://` 路由） | `EurekaServerApplication`（`@EnableEurekaServer`） |
| **config-server** | 8888 | 配置中心 | 集中管理 `user-service` 和 `order-service` 的配置（端口、Eureka 地址等）。本地文件方式（`spring.profiles.active: native`），模拟 Git 仓库 | `application.yml` → `search-locations: classpath:/config/`；配置项在 `config/user-service.yml`、`config/order-service.yml` |
| **user-service** | 8081 | 用户服务（被调用方） | 提供用户查询接口 `GET /user/{id}` → `{"id":1,"name":"User-1"}`。自身配置（8081 端口、注册到 Eureka）来自 config-server | `UserController.java`、`User.java` |
| **order-service** | 8082 | 订单服务（调用方） | 入口 `GET /order/{orderId}`，通过 **OpenFeign** `UserClient.getUser()` 调 user-service 拿用户信息，拼成订单+用户返回；带 **Resilience4j 熔断降级**（`@CircuitBreaker(name="orderService", fallbackMethod="fallback")`） | `OrderController.java`、`client/UserClient.java`（Feign）、`model/User.java` |
| **gateway** | 8000 | 网关（统一入口） | 对外唯一入口。按路径路由：`/api/user/** → lb://user-service`、`/api/order/** → lb://order-service`（路由规则在 `application.yml`，模式是 Native Gateway `RouteLocator` bean） | `GatewayApplication.java` |

**请求链路（一次调用怎么串起来）**
```
浏览器/curl
   │  http://localhost:8000/api/order/1
   ▼
gateway（8000，统一入口，路由）
   │  lb://order-service（走 Eureka 找实例）
   ▼
order-service（8082）
   │  Feign + LoadBalancer（按服务名 user-service 再从 Eureka 找）
   ▼
user-service（8081）
   │  返回 {"id":1,"name":"User-1"}
   ▼
order-service 拼装 → {"orderId":1,"user":{"id":1,"name":"User-1"}}
```

**依赖关系**：eureka-server / config-server 是基础设施（先起）；user/order 依赖 config-server 的配置和 eureka 的注册；gateway 依赖 eureka。compose 里已用 `depends_on: condition: service_healthy` 保证先后。

## 2. 方式一：Docker 一键部署（推荐）

### 2.1 Docker 引擎：Docker Desktop + WSL2（本机已就绪）

本机 Docker 用的是 **Docker Desktop（version 29.6.2）**，容器引擎跑在 Windows 侧，WSL 的
`docker` 直接连它（WSL Integration 已开启 Ubuntu-22.04）。这样：
- 容器会**显示在 Docker Desktop GUI 的 Containers 页**里，方便查看/停启；
- 不用再单独维护 WSL 里的 `dockerd`（两者端口/命名空间冲突，优先用 Desktop 引擎）。

```powershell
# 启动 Docker Desktop（主程序路径）
Start-Process "C:\Users\admin\AppData\Local\Programs\DockerDesktop\Docker Desktop.exe"

# 改完 daemon.json / settings 后重启生效
Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Name -match 'Docker Desktop|com.docker' } | Stop-Process -Force
Start-Process "C:\Users\admin\AppData\Local\Programs\DockerDesktop\Docker Desktop.exe"

# 等引擎就绪后确认 WSL 里的 docker 连的是 Desktop 引擎
docker info --format '{{.ServerVersion}}'   # 应显示 29.6.2，而非 WSL 独立 dockerd
```

> 镜像加速器已写进 `C:\Users\admin\.docker\daemon.json`（4 个国内镜像），
> `docker info` 看到 `Registry Mirrors` 即为生效。

### 2.2 构建 jar
```bash
cd spring-cloud-native-demo
mvn clean package -DskipTests
```

### 2.3 启动
```bash
docker compose up -d --build
```

> 一条命令启动全部 5 个服务。顺序由 `depends_on` + **健康检查**保证：
> user/order 会等 config-server 真正就绪（`condition: service_healthy`）后才启动，
> 避免"配置中心还没起好导致业务服务拿到默认配置、注册到错误地址"的问题。

### 2.4 查看状态
```bash
docker compose ps
docker compose logs -f gateway     # 实时看某个服务的日志
```

### 2.5 验证
```bash
# Eureka 控制台（能看到 user/order/gateway 等服务注册）
浏览器打开 http://localhost:8761

# 走网关 -> user-service
curl http://localhost:8000/api/user/1
# => {"id":1,"name":"User-1"}

# 走网关 -> order-service -> Feign 调 user-service
curl http://localhost:8000/api/order/1
# => {"orderId":1,"user":{"id":1,"name":"User-1"}}
```

> 本机已实测通过：Eureka 显示 `GATEWAY / ORDER-SERVICE / USER-SERVICE`，
> 上两个 curl 均为 200 返回正确 JSON。

### 2.6 停止
```bash
docker compose down
```

## 3. 方式二：本地直接跑（不用 Docker）

按依赖顺序，每个命令**新开一个终端**：

```bash
mvn spring-boot:run -pl eureka-server   # 先注册中心
mvn spring-boot:run -pl config-server   # 再配置中心
mvn spring-boot:run -pl user-service
mvn spring-boot:run -pl order-service
mvn spring-boot:run -pl gateway
```

## 4. 功能验证

### 4.1 熔断降级测试（Resilience4j）
1. 容器方式：`docker compose stop user-service`
2. 连续调用：
   ```bash
   curl http://localhost:8000/api/order/1
   ```
3. 失败 3 次后（`minimum-number-of-calls: 3`）熔断器打开，返回：
   ```json
   {"orderId":1,"user":"fallback user","reason":"FeignFeignException..."}
   ```

### 4.2 配置中心
- 配置在 `config-server/src/main/resources/config/`（模拟 Git 仓库）
- 改完需要**重新构建 config-server 镜像**才会生效（`docker compose up -d --build config-server`）

## 5. 常见问题（本机踩过的坑）

| 问题 | 解决 |
|------|------|
| 构建报 Java 版本错误 | 确认 `java -version` 是 21，Maven 用 `mvn -version` 看 JAVA_HOME |
| Docker Hub 拉镜像慢/超时 | 已配置好的 `C:\Users\admin\.docker\daemon.json` 镜像加速器，改完重启 Docker Desktop |
| **服务能起但没注册进 Eureka（网关 503）** | 根因：user/order 比 config-server 先就绪，`optional:configserver` 拉配置失败 → 用了默认 Eureka 地址 `localhost:8761`（容器内 localhost 连不上）。**解法：compose 里给 config/eureka 加 healthcheck，user/order/gateway 用 `depends_on: condition: service_healthy` 等配置中心真正就绪**，改完 `docker compose up -d --build` |
| WSL 空闲一会儿所有容器/进程消失 | 默认 WSL VM 无会话空闲 ~60s 自动关闭。在 `C:\Users\admin\.wslconfig` 写 `vmIdleTimeout=43200000`（12 小时）并 `wsl --shutdown` 生效 |
| 容器不在 Docker Desktop GUI 显示 | 说明 compose 没跑在 Desktop 引擎上（WSL 里有独立 dockerd）。先 `Get-Process ... | Stop-Process` 重启 Desktop，确认 `docker info --format '{{.ServerVersion}}'` 是 29.6.2 再 `docker compose up -d --build` |
| 端口被占用 | 改 `docker-compose.yml` 里的宿主机端口映射 |
| 服务调用 404 | 确认 gateway 路由的 `Path` 与服务端 `@GetMapping` 路径对应 |

## 6. Docker 引擎二选一（本机已用 Docker Desktop）

**方案 A：Docker Desktop（本机现用，推荐）**
- 安装路径 `C:\Users\admin\AppData\Local\Programs\DockerDesktop\`
- daemon 配置 `C:\Users\admin\.docker\daemon.json`（已配 4 个国内镜像加速器）
- WSL 集成已开（settings → Resources → WSL Integration → Ubuntu-22.04 勾选）
- 容器的 GUI 体验最好，直接在 Desktop 里看/停/启

**方案 B：WSL 里独立 dockerd（不推荐，会和 Desktop 引擎冲突）**
```bash
wsl -l -v                        # 确认 WSL2
# 阿里云镜像源安装 docker-ce ...
sudo service docker start
sudo usermod -aG docker $USER
```

> 二选一即可。若两个都在跑，先停 WSL 里的 `dockerd`，保证 `docker` 统一连 Desktop 引擎。

## 7. 原理解读：组件详解 + 原生 vs Alibaba

### 7.1 已退役组件（别再用老教程）

| 退役组件 | 原因 | 官方平替 |
|----------|------|----------|
| **Ribbon**（客户端负载均衡） | Netflix 停维护，Spring Cloud 2020 起从 BOM 移除 | **Spring Cloud LoadBalancer**（官方新实现，默认轮询） |
| **Hystrix**（熔断） | 已停止维护 | **Resilience4j**（官方主推，按方法粒度熔断/限流/重试，本 demo 已用） |
| **Zuul**（网关） | 已归档，基于旧 Servlet | **Spring Cloud Gateway**（响应式 WebFlux，本 demo 已用） |

> 判断新老教程：出现 `netflix-ribbon`、`@HystrixCommand`、`netflix-zuul` 依赖的就是旧版，别照抄。

### 7.2 原生组合逐组件要点

| 组件 | 本 demo 位置 | 要点 |
|------|--------------|------|
| **Eureka** | eureka-server (8761) | 服务注册/发现/心跳；业务端 `defaultZone` 配错就会注册不上（见常见问题） |
| **Config Server** | config-server (8888) | 集中管配置，当前 `native` 模式（`classpath:/config/`），生产换 Git 后端；客户端 `optional:configserver` 不阻塞启动，但要保证"它先就绪" |
| **OpenFeign** | order-service `UserClient` | `@FeignClient("user-service")` 按服务名调用，自动走 Eureka 发现 |
| **LoadBalancer** | 各业务模块 | 与 Feign 自动集成，`lb://` 按服务名解析实例，默认轮询 |
| **Resilience4j** | order-service `@CircuitBreaker` | `minimum-number-of-calls` / `failure-rate-threshold` 达标后熔断进 fallback |
| **Gateway** | gateway (8000) | 统一入口，`/api/user/** -> lb://user-service`，依赖 Eureka |

### 7.3 组件对比（原生 vs Alibaba）

| 功能 | 原生 Spring Cloud | Spring Cloud Alibaba |
|------|-------------------|----------------------|
| 注册中心 | Eureka / Consul / Nacos | **Nacos** |
| 配置中心 | Config Server | **Nacos** |
| 服务调用 | OpenFeign | OpenFeign |
| 网关 | Gateway | Gateway |
| 熔断限流 | Resilience4j | **Sentinel** |
| 负载均衡 | LoadBalancer | LoadBalancer |

**本质差异**：Alibaba 用 Nacos 一个组件合并了"注册 + 配置"两份活（无需 Eureka/Config 两个独立服务），熔断从 Resilience4j 换成带控制台的 Sentinel（实时限流规则、可视化）；OpenFeign / Gateway / LoadBalancer 两边通用。

> **2026 新项目原生推荐组合**：Eureka + Config + OpenFeign + Spring Cloud LoadBalancer + Resilience4j + Gateway（正是本 demo 的组合）。

## 8. 代码详解：每个 module 的 yaml / pom / java

### 8.0 根 pom.xml（聚合 + 版本管理）

| 内容 | 作用 |
|------|------|
| `parent: spring-boot-starter-parent 3.2.5` | 继承 Spring Boot 版本管理，锁定 Spring Boot 全家桶依赖版本 |
| `packaging: pom` + `<modules>` 列 5 个 module | 根工程是**聚合工程**，`mvn package` 一次构建全部 5 个模块 |
| `<java.version>21</java.version>` | 编译目标 Java 21 |
| `<spring-cloud.version>2023.0.1</spring-cloud.version>` | 引入 `spring-cloud-dependencies` BOM：Spring Cloud 组件版本统一管理，各 module 引用 starter 时**不用写版本号** |
| `spring-boot-maven-plugin` 放 pluginManagement | 各子模块继承，打包成可执行 fat-jar |

### 8.1 eureka-server（注册中心，9761——哦不 8761）

**pom.xml**：唯一依赖 `spring-cloud-starter-netflix-eureka-server`（Eureka 服务端）。

**application.yml**
```yaml
server:
  port: 8761                      # 注册中心端口
spring:
  application:
    name: eureka-server
eureka:
  client:
    register-with-eureka: false   # 自己作为注册中心，不注册到自己
    fetch-registry: false         # 不需要拉取别人（自己就是唯一注册中心）
  server:
    enable-self-preservation: false  # 关闭自我保护，方便 demo 中服务下线能及时剔除
```

**Java**：`EurekaServerApplication.java`
```java
@EnableEurekaServer   // 关键注解：开启 Eureka Server 服务端
@SpringBootApplication
public class EurekaServerApplication { ... }
```

### 8.2 config-server（配置中心，8888）

**pom.xml**：唯一依赖 `spring-cloud-config-server`。

**application.yml**
```yaml
server:
  port: 8888
spring:
  application:
    name: config-server
  profiles:
    active: native                 # 配置仓库用本地文件方式
  cloud:
    config:
      server:
        native:
          search-locations: classpath:/config/   # 配置放在 classpath:/config/ 下
```
> 生产环境把 `native` 换成 Git：`spring.cloud.config.server.git.uri`。

**配置文件（config/ 下的两个 yml，这才是真正的"配置内容"）**
- `config/user-service.yml`：`server.port=8081` + `eureka.client.service-url.defaultZone=${EUREKA_DEFAULT_ZONE:http://localhost:8761/eureka/}`（defaultZone 支持环境变量覆盖，docker 里传 `EUREKA_DEFAULT_ZONE=http://eureka-server:8761/eureka/`）
- `config/order-service.yml`：同样 8082 端口 + defaultZone，另外**追加了熔断器参数**：
```yaml
resilience4j:
  circuitbreaker:
    instances:
      orderService:                # 与 @CircuitBreaker(name="orderService") 对应
        failure-rate-threshold: 50      # 失败率超过 50% 触发熔断
        minimum-number-of-calls: 3      # 最少统计 3 次才判断
        sliding-window-size: 5          # 统计窗口 5 次调用
        wait-duration-in-open-state: 10s  # 熔断保持 10s 后尝试半开
```

**Java**：`ConfigServerApplication.java` 唯一不同是 `@EnableConfigServer`。

### 8.3 user-service（用户服务，8081）

**pom.xml** 三个依赖，各司其职：
- `spring-boot-starter-web` → 提供 HTTP 接口
- `spring-cloud-starter-config` → 启动时从 config-server 拉配置（拿 8081 端口、Eureka 地址）
- `spring-cloud-starter-netflix-eureka-client` → 把自己注册进 Eureka

**application.yml**
```yaml
spring:
  application:
    name: user-service                          # 配置中心按这个名字找 user-service.yml
  config:
    import: optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888}  # 导入配置中心；optional 表示配置中心挂了不阻塞启动
```

**Java**
- `UserServiceApplication.java`：普通 `@SpringBootApplication`（不写 `@EnableDiscoveryClient` 也能自动注册，Spring Cloud 2020+ 默认开启）。
- `UserController.java`
```java
@RestController
public class UserController {
    @GetMapping("/user/{id}")        // 提供 GET /user/{id}
    public User getUser(@PathVariable Long id) {
        return new User(id, "User-" + id);   // 假数据：User-1
    }
}
```
- `User.java`：Java 21 的 `record`（`Long id, String name`），紧凑的不可变数据类，自动生成构造器/getter/equals/hashCode。

### 8.4 order-service（订单服务，8082，核心演示）

**pom.xml** 五个依赖（比 user 多三个）：
- `spring-boot-starter-web` + `spring-cloud-starter-config` + `spring-cloud-starter-netflix-eureka-client`（同 user）
- `spring-cloud-starter-loadbalancer` → Feign 按服务名调用时做负载均衡
- `spring-cloud-starter-openfeign` → 声明式调用 user-service
- `spring-cloud-starter-circuitbreaker-resilience4j` → 熔断降级

**application.yml**：与 user-service 完全一样（name=order-service，`import: optional:configserver`）。

**Java**
- `OrderServiceApplication.java`：多一个 `@EnableFeignClients`（扫描 `@FeignClient` 接口生成代理）。
- `client/UserClient.java`（Feign 声明接口）
```java
@FeignClient(name = "user-service")   // 按服务名调用，自动走 Eureka 找实例 + LoadBalancer 选一台
public interface UserClient {
    @GetMapping("/user/{id}")         // 对应 user-service 的接口
    User getUser(@PathVariable Long id);
}
```
- `model/User.java`：与 user-service 同形态的 `record`（Feign 反序列化响应用）。
- `OrderController.java`（熔断主逻辑）
```java
@CircuitBreaker(name = "orderService", fallbackMethod = "fallback")  // 熔断器名与 config 里一致
@GetMapping("/order/{orderId}")
public Map<String, Object> getOrder(@PathVariable Long orderId) {
    User user = userClient.getUser(orderId);   // 远程调 user-service
    return Map.of("orderId", orderId, "user", user);
}
public Map<String, Object> fallback(Long orderId, Throwable t) {     // 降级方法：参数(原参数, Throwable)
    return Map.of("orderId", orderId, "user", "fallback user", "reason", t.getClass().getSimpleName());
}
```

### 8.5 gateway（网关，8000）

**pom.xml** 三个依赖：`spring-cloud-starter-gateway`（响应式网关）+ `netflix-eureka-client`（要向 Eureka 注册自己，供配置中心/控制台发现）+ `loadbalancer`（`lb://` 路由解析实例）。

**application.yml**（本工程唯一在 yaml 里直接写路由规则的）
```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: user-route
          uri: lb://user-service          # lb:// 前缀 = 经 LoadBalancer + Eureka 找 user-service 实例
          predicates: [ Path=/api/user/** ]  # 匹配 /api/user/xx 的请求
          filters: [ StripPrefix=1 ]         # 去掉 /api 前缀，转发成 user-service 的 /user/xx
        - id: order-route
          uri: lb://order-service
          predicates: [ Path=/api/order/** ]
          filters: [ StripPrefix=1 ]
```

**Java**：`GatewayApplication.java` 就是普通 `@SpringBootApplication`（路由直接用 yaml 定义，无需代码）。

**一句话看懂转发**：`GET /api/user/1` → 匹配 user-route → `StripPrefix` 去掉 `/api` → `lb://user-service` 找到实例 → 实际请求 `user-service` 的 `GET /user/1`。