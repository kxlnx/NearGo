<div align="center">

# Java 8 常用特性与 NearGo（hmdp）项目实战

</div>

> 目标：不只记“特性是什么”，而是知道它在 NearGo 项目里什么时候用、怎么用、哪些地方不要乱用。
> 场景以 NearGo（黑马点评二次开发）为主：店铺缓存、优惠券秒杀、订单支付与超时关单、RocketMQ 异步、XXL-JOB 对账、Redis + Lua、Caffeine、滑动窗口限流。
>
> **重要前提：本项目是 JDK 8（`pom.xml`：`maven.compiler.source/target = 8`，Spring Boot 2.7.4）。**
>
> 全文分两部分：
> **第一篇：JDK 特性部分** —— **Java 8 / 17 / 21 的“新功能”逐个讲清**（例子是通用代码，不绑定本项目）；
> **第二篇：项目实战部分（重点多线程）** —— 创建线程的方式、返回值与异常的区别、项目里真实的线程全景、为什么手写多线程少。

---

# 第一篇：JDK 特性部分（Java 8 / 17 / 21 新功能）

## 1. 版本总览

| 版本 | 新增的关键功能 | 备注 |
|---|---|---|
| **Java 8** | Lambda、函数式接口、方法引用、Stream、Collectors、Optional、java.time、接口 default 方法、CompletableFuture | 项目实际在用的版本 |
| **Java 17** | Record、Text Block、instanceof 模式匹配、Sealed Class | 现代 Java 的稳定基建 |
| **Java 21** | 虚拟线程、switch 模式匹配、Record Pattern、Sequenced Collections | 高并发现代写法 |

> 本篇所有例子都是**通用示例**（用户、字符串、订单这类），不绑定具体项目；这些知识在 NearGo 里到底用了哪些、怎么用，见第二篇。

---

## 2. Java 8 新功能

### 2.1 Lambda 表达式

Lambda 是 **Java 8** 引入的“把行为当成参数传”的语法。以前写匿名内部类：

```java
// 以前：匿名内部类
list.sort(new Comparator<String>() {
    @Override
    public int compare(String a, String b) {
        return a.compareTo(b);
    }
});
```

Java 8 之后：

```java
// Lambda
list.sort((a, b) -> a.compareTo(b));

// 进一步：方法引用
list.sort(String::compareTo);
```

要点：Lambda 必须有**函数式接口**（只有一个抽象方法的接口）作为目标类型。

> Lambda 最常见的搭档是 **Stream**：`filter / map / sorted` 这些**中间操作**的参数全部是函数式接口（详见 2.4）。

### 2.2 函数式接口

四大常用接口，最简单的理解方式：

```java
Predicate<String> notEmpty = s -> !s.isEmpty();          // 判断：给我一个值，返回 true/false
Function<String, Integer> length = String::length;       // 转换：String → Integer
Consumer<String> printer = s -> System.out.println(s);   // 消费：只进不出
Supplier<String> supplier = () -> "hello";               // 提供：不进只出
```

也可以自己定义（加 `@FunctionalInterface` 后编译器会帮你检查）：

```java
@FunctionalInterface
public interface Greeting {
    String hello(String name);
}

Greeting g = name -> "你好，" + name;
g.hello("张三");
```

### 2.3 方法引用

Lambda 的简写：当 Lambda 体只是“调用一个现成方法”时，可以写成方法引用。

```text
String::length            // 类::实例方法（入参当调用者）
System.out::println       // 对象::实例方法
User::getName             // 类::实例方法（入参是 User）
ArrayList::new            // 构造器引用
```

```java
list.forEach(System.out::println);       // 等价于 list.forEach(s -> System.out.println(s))
```

### 2.4 Stream API

Stream 是对**内存集合**的声明式处理流水线：`集合 → stream() → 中间操作 → 终结操作`。

```java
List<String> names = List.of("张三", "李四", "王五", "张三");

List<String> result = names.stream()
        .filter(n -> n.startsWith("张"))    // 过滤
        .distinct()                         // 去重
        .map(String::toUpperCase)           // 转换
        .sorted()                           // 排序
        .collect(Collectors.toList());      // 收集
```

**中间操作**（返回的还是 Stream，可以继续链式调用，而且是“惰性”的）：

| 操作 | 作用 | 例子 |
|---|---|---|
| `filter` | 筛选 | `.filter(n -> n.startsWith("张"))` |
| `map` | 一对一转换 | `.map(String::toUpperCase)` |
| `flatMap` | 一对多拍平 | `.flatMap(list -> list.stream())` |
| `distinct` | 去重 | `.distinct()` |
| `sorted` | 排序 | `.sorted()` / `.sorted(Comparator.reverseOrder())` |
| `limit / skip` | 截断 / 跳过 | `.limit(3)` / `.skip(1)` |
| `peek` | 偷看（调试用） | `.peek(System.out::println)` |

**终结操作**（触发执行、产生结果；一条流只能终结一次）：

| 操作 | 作用 | 例子 |
|---|---|---|
| `collect` | 收集成集合 | `.collect(Collectors.toList())` |
| `count` | 计数 | `.count()` |
| `anyMatch / allMatch` | 判断 | `.anyMatch(n -> n.isBlank())` |
| `findFirst` | 找第一个 | `.findFirst()`（返回 Optional） |
| `forEach` | 逐个消费 | `.forEach(System.out::println)` |
| `reduce` | 聚合 | `.reduce(0, Integer::sum)` |

**惰性求值（重点）：** 中间操作只是“记下来”，**不写终结操作就什么都不会执行**：

```java
names.stream().filter(n -> {
    System.out.println("执行 filter：" + n);   // 这行一次都不会打印
    return n.startsWith("张");
});
// 没有终结操作 → 整条流根本没跑
```

**注意：** `stream()` 默认还是单线程，**不会天然变快**；它的价值是表达清晰、少写循环。

### 2.5 Collectors（收集器）

```java
// 1) 分组：按长度分组
Map<Integer, List<String>> byLength = names.stream()
        .collect(Collectors.groupingBy(String::length));

// 2) 分组后计数
Map<Integer, Long> countByLength = names.stream()
        .collect(Collectors.groupingBy(String::length, Collectors.counting()));

// 3) 转 Map（出现重复 key 必须给合并函数，否则抛异常）
Map<Integer, String> map = names.stream()
        .collect(Collectors.toMap(String::length, Function.identity(), (a, b) -> a));
```

> 数据在数据库里时，优先让 SQL `GROUP BY` 做，而不是查出来再在 Java 里分组。

### 2.6 Optional（防空指针的容器）

```java
// 以前
User user = userMap.get(id);
if (user == null) {
    throw new IllegalArgumentException("用户不存在");
}

// Java 8
User user = Optional.ofNullable(userMap.get(id))
        .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
```

`orElse` 与 `orElseGet` 的区别：

```java
optional.orElse(loadFromDb());        // 不管有没有值，loadFromDb() 都会执行（可能白查一次库）
optional.orElseGet(this::loadFromDb); // 只有为空才执行
```

### 2.7 java.time（新的日期时间 API）

```java
LocalDate today = LocalDate.now();                  // 日期
LocalDateTime now = LocalDateTime.now();            // 日期 + 时间
LocalDateTime after30s = now.plusSeconds(30);       // 加 30 秒
Instant epoch = Instant.now();                      // 机器时间戳（适合存/传输）
Duration d = Duration.between(now, after30s);       // 时间差
```

为什么不用老的 `Date / Calendar`：可变、线程不安全、API 反直觉。

### 2.8 接口默认方法（default）

Java 8 之前接口只能有抽象方法；Java 8 允许写默认实现（子类不强制重写）：

```java
public interface Animal {
    void eat();

    default void sleep() {          // 默认方法
        System.out.println("zzz...");
    }
}
```

### 2.9 CompletableFuture（异步编排，详见第二篇）

Java 8 新加的异步编程工具：

```java
CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> "结果");
f.thenApply(String::toUpperCase).thenAccept(System.out::println);
```

适合“多个独立 IO 并行 + 聚合”；线程模型、返回值与异常的处理见第二篇。

---

## 3. Java 17 新功能

### 3.1 Record（不可变数据载体）

一行代码 = 构造器 + 访问器 + `equals/hashCode/toString`：

```java
public record User(Long id, String name) {}

User u = new User(1L, "张三");
System.out.println(u.id());      // 访问器是 id()，不是 getId()
System.out.println(u.name());
```

适合：DTO、接口响应、事件对象等“只表示一组数据”的场景；字段不可变，不适合需要 setter 的实体。

### 3.2 Text Block（多行字符串）

```java
// 以前要拼接和转义
String json = "{\n" +
        "  \"name\": \"张三\",\n" +
        "  \"age\": 18\n" +
        "}";

// Java 17
String json = """
        {
          "name": "张三",
          "age": 18
        }
        """;
```

适合：SQL、JSON、HTML、测试数据。

### 3.3 instanceof 模式匹配

```java
// 以前：判断 + 手动强转
if (obj instanceof String) {
    String s = (String) obj;
    System.out.println(s.length());
}

// Java 17：判断的同时声明变量
if (obj instanceof String s) {
    System.out.println(s.length());
}
```

### 3.4 Sealed Class（受限制的继承）

```java
// 只允许 Circle 和 Square 实现这个接口，别人不能再扩展
public sealed interface Shape permits Circle, Square {}

public record Circle(double radius) implements Shape {}
public record Square(double side) implements Shape {}
```

适合：固定的“事件/状态/命令”体系；配合模式匹配，编译器可以检查是否写全。

---

## 4. Java 21 新功能

### 4.1 虚拟线程（Virtual Threads）

```java
// 方式一：直接启动
Thread.startVirtualThread(() -> System.out.println("hello"));

// 方式二：一个任务一个虚拟线程的执行器
try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    Future<String> f = executor.submit(() -> httpGet("https://example.com"));
    System.out.println(f.get());
}
```

- 适合：**大量阻塞 IO**（HTTP、数据库、文件）——以前“一个请求一个平台线程”成本高（每个约 1MB 栈），虚拟线程由 JVM 调度、极轻量；
- 不适合：CPU 密集任务（线程再多也不会凭空变快）。

### 4.2 switch 模式匹配

```java
String describe = switch (shape) {
    case Circle c -> "圆，半径 " + c.radius();
    case Square s -> "方，边长 " + s.side();
    default       -> "未知形状";
};
```

配合 Sealed 接口还能省掉 `default`（编译器知道你已经写全）。

### 4.3 Record Pattern（记录解构）

```java
// 直接把 record 的字段“拆”出来用
if (obj instanceof User(Long id, String name)) {
    System.out.println(id + " -> " + name);
}
```

### 4.4 Sequenced Collections（统一首尾操作）

```java
list.getFirst();
list.getLast();
list.reversed();     // 反转视图
```

以前 `List / Deque / LinkedHashSet` 各有一套首尾 API，Java 21 统一成 `SequencedCollection` 接口。

---

> 第一篇到这里：**Java 8 / 17 / 21 各加了什么、怎么写、什么场景用**就齐了。
> 第二篇开始讲这些知识在 NearGo 项目里的落地——重点是**多线程与并发**。

---

# 第二篇：项目实战部分（重点：多线程与并发）

## 1. 先说结论：为什么项目里“手写多线程”很少

**先破除一个误解：项目并不是没有多线程，它一直多线程在跑，只是业务代码很少自己创建线程。**

### 1.1 项目线程全景

| 分类 | 线程 | 谁创建的 |
|---|---|---|
| **手写** | `CACHE_REBUILD_EXECUTOR`（固定 10 线程，缓存异步重建） | 项目代码（CacheClient.java:183） |
| 手写（工具） | `UserHolder` 的 ThreadLocal（登录上下文） | 项目代码 |
| 框架 | Tomcat 请求线程池（默认 max 200） | Spring Boot |
| 框架 | RocketMQ 消费线程池（两个 Listener 都 `CONCURRENTLY`） | RocketMQ |
| 框架 | Redisson 的 Netty event loop + 看门狗定时任务 | Redisson |
| 框架 | XXL-JOB 每个 JobHandler 一条 JobThread | XXL-JOB |

### 1.2 手写少的原因（设计决定，不是不会）

**原因一：并发控制全部“下沉”到了共享组件，不在 JVM 里。**

```text
库存不超卖 / 一人一单    → Redis + Lua（原子执行）
一人多单 / 重复消费       → MQ 消费端幂等（订单ID + 用户×券 + 锁）
支付/关单竞争            → 数据库条件更新（乐观锁）
缓存重建竞争             → Redis SETNX 互斥锁
限流计数                 → Redis ZSet + Lua
```

`synchronized`、并发容器只对**单实例**有效；项目是“多实例 + Redis + MySQL”形态，并发状态必须放在共享存储里，放在 JVM 内存里解决不了跨实例问题。

**原因二：需要异步的地方选了 MQ，而不是线程池。**

秒杀要“削峰 + 解耦”：线程池在接口里开线程做落库，服务一重启任务就丢、没法重试；MQ 有持久化、有重试、能跨实例消费——**线程池能做的异步，MQ 做得更可靠**。

**原因三：请求级并发由 Tomcat 提供。**

“一个 HTTP 请求一个线程”就是 Web 的并发模型，业务逻辑不需要再自己开线程；真正的例外只有缓存重建（请求线程不想等查库），所以才有了那一个线程池。

**原因四：项目是 Redis 教学项目。**

黑马点评聚焦缓存、秒杀、分布式锁，JUC 没有教学场景——不是做不到，是没用到。

**面试口径一句话**：“项目的并发问题不是靠 JVM 线程解决的——库存和一人一单用 Redis+Lua，重复下单用 MQ 幂等+Redisson 锁，状态竞争用数据库条件更新；唯一的自建线程池是缓存异步重建。因为多实例部署，JVM 锁解决不了跨实例问题。”

---

## 2. 创建线程的几种方式（逐个讲清返回值与异常）

### 2.1 继承 Thread（不推荐）

```java
public class RebuildThread extends Thread {
    @Override
    public void run() {
        // 查库 + 回写缓存
    }
}
new RebuildThread().start();
```

问题：

1. Java 单继承，继承了 Thread 就不能再继承别的类；
2. 任务和线程耦合，任务无法复用；
3. 每次 `new` 都要创建/销毁线程，成本高；
4. 没有池化、没有队列、没有拒绝策略——**缓存重建如果用这种写法，流量一高就是线程爆炸**。

结论：项目不用；这类“提交任务给后台执行”的场景，正确姿势是 Runnable/Callable + 线程池。

### 2.2 实现 Runnable（无返回值）——项目真实用法

CacheClient 的缓存重建（`CacheClient.java:162-174`，这里用简化示意）：

```java
if (flag) {                                   // flag = 抢到锁的线程才负责重建
    CACHE_REBUILD_EXECUTOR.submit(() -> {     // 把任务丢给线程池，异步执行
        try {
            Shop shop = getShopFromDb(id);    // 1. 查数据库（慢操作）
            saveShopToCache(key, shop);        // 2. 把新数据写回缓存
        } catch (Exception e) {
            throw new RuntimeException(e);    // Runnable 不能抛受检异常，只能包一层
        } finally {
            unLock(lockKey);                  // 3. 无论成败，锁必须释放
        }
    });
}
return oldShop;   // 请求线程不等重建，直接返回旧值
```

特点（面试要能说清）：

- `void run()`：**没有返回值**，任务执行完就完了；
- **不能抛受检异常**：`run()` 没有 `throws`，受检异常只能自己包成运行时异常（上面 `throw new RuntimeException(e)` 就是这个原因）；
- 异常去哪：取决于怎么提交——`execute` 走线程池的未捕获异常处理，`submit` 会被封装进 Future（本项目**没有取 Future，异常实际被吞了**，见第 3.4 节，这是一个真实的隐患点）。

### 2.3 实现 Callable + FutureTask（有返回值、可抛受检异常）

场景：如果重建任务需要“告诉调用方成功失败”，就用 Callable：

```java
Callable<Boolean> rebuildTask = () -> {
    try {
        Shop shop = getShopFromDb(id);   // 查数据库
        saveShopToCache(key, shop);       // 回写缓存
        return true;                      // ← 有返回值：告诉调用方“重建成功”
    } finally {
        unLock(lockKey);
    }
};

FutureTask<Boolean> futureTask = new FutureTask<>(rebuildTask);  // 既实现了 Runnable，又实现了 Future
CACHE_REBUILD_EXECUTOR.execute(futureTask);                      // 当 Runnable 提交

// 需要结果时（会阻塞当前线程！）：
boolean ok = futureTask.get();       // 受检异常：InterruptedException / ExecutionException
```

特点：

- **有返回值**：结果通过 `Future/FutureTask` 拿；
- **可以抛受检异常**（`call()` 声明了 `throws Exception`），但 `get()` 时会被包成 `ExecutionException`；
- 执行结果只能提交给线程池（或包成 FutureTask 再交给 Thread）。

**项目为什么没用 Callable**：缓存重建是“发起后不关心结果”的场景——请求线程要立刻返回旧值，如果去 `get()` 等结果反而把自己阻塞了，违背异步重建的初衷。所以用 Runnable 更合适。

### 2.4 线程池：ExecutorService / ThreadPoolExecutor（最常用的方式）

严格说，线程池自己“创建线程”的动作由**线程工厂**完成；我们提交的是**任务**，线程由池子创建、复用、回收。这就是它和 `new Thread()` 的本质区别：

```text
new Thread()   每次新建一条线程，用完销毁         —— 不可控
线程池         预先/按需创建线程并复用，任务排队   —— 可控（并发数、队列、拒绝策略）
```

**两种创建路径。**

路径一：`Executors` 快捷工厂（项目用的就是它）：

```java
// 项目真实代码（CacheClient.java:183）
private static final ExecutorService CACHE_REBUILD_EXECUTOR =
        Executors.newFixedThreadPool(10);
```

| 工厂方法 | 内部结构 | 特点 | 风险 |
|---|---|---|---|
| `newFixedThreadPool(n)` | core=max=n + 无界队列 | 固定并发、任务排队 | 队列无界 → 任务堆积 OOM |
| `newCachedThreadPool()` | core=0、max=Integer.MAX_VALUE | 来任务就复用/新建 | 线程数无上限 → 线程爆炸 |
| `newSingleThreadExecutor()` | 1 线程 + 无界队列 | 串行、保序 | 同无界队列 |
| `newScheduledThreadPool(n)` | 支持定时/周期任务 | 定时调度 | 队列无界 |
| `newWorkStealingPool()` | ForkJoin 窃取 | CPU 型并行 | 不适合阻塞 IO |

> 《阿里巴巴 Java 开发手册》禁止用 `Executors` 快捷方法（无界队列 / 线程数无上限），要求显式 `new ThreadPoolExecutor`。

路径二：显式 `ThreadPoolExecutor`（推荐，面试标准答案）：

```java
ThreadPoolExecutor executor = new ThreadPoolExecutor(
        4,                                        // corePoolSize 核心线程数
        8,                                        // maximumPoolSize 最大线程数
        60, TimeUnit.SECONDS,                     // 非核心线程空闲回收时间
        new ArrayBlockingQueue<>(1000),           // 有界队列：防 OOM
        r -> new Thread(r, "cache-rebuild"),      // 线程工厂：给线程命名（ThreadFactory 是函数式接口）
        new ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略：调用线程兜底
);
```

**任务提交后，线程池是怎么处理的（线程创建/复用规则）：**

```text
提交任务
  ├─ 运行线程数 < corePoolSize     → 新建核心线程执行
  ├─ 核心已满 → 任务进队列等待
  ├─ 队列已满且 < maximumPoolSize → 新建非核心线程执行
  └─ 队列满且已达 maximumPoolSize → 触发拒绝策略
（非核心线程空闲超过 keepAliveTime 回收；核心线程默认常驻）
```

**七个核心参数怎么定（结合项目“重建线程池”）：**

| 参数 | 含义 | 项目取值思路 |
|---|---|---|
| corePoolSize | 核心线程数 | 重建是低频 IO，4~10 足够 |
| maximumPoolSize | 最大线程数 | 略大于 core，抗小突发 |
| keepAliveTime / unit | 非核心线程空闲回收 | 60s |
| workQueue | 等待队列 | **必须有界**（ArrayBlockingQueue(1000)） |
| threadFactory | 线程创建方式 | 命名 `cache-rebuild-1` 等 |
| handler | 拒绝策略 | `CallerRunsPolicy`（不丢任务） |

**四种拒绝策略：**

```text
AbortPolicy（默认）   抛 RejectedExecutionException
CallerRunsPolicy      调用线程自己执行（反压、不丢任务）
DiscardPolicy         静默丢弃
DiscardOldestPolicy   丢最老的任务再重试
```

**线程数怎么估：**

- CPU 密集：约等于 CPU 核数（+1）；
- IO 密集：核数 ×（1 + 等待时间 / 计算时间），但要受数据库连接池、Redis 连接池、下游容量约束。

> 回到项目：重建线程池固定 10 个线程，是因为这是 IO 低频任务、且要防止“重建风暴”把线程打爆；如果改造，把 `newFixedThreadPool(10)` 换成上面的显式写法即可。

### 2.5 提交任务：execute 与 submit（返回值与异常的区别）

**写法一：无返回值提交 —— `execute(Runnable)`**

任务只是“干活”，不关心结果：

```java
ExecutorService executor = Executors.newFixedThreadPool(2);

// 定义任务：一件事，不产生结果
Runnable task = () -> System.out.println("执行任务，线程：" + Thread.currentThread().getName());

executor.execute(task);     // 提交即结束：没有返回值，也拿不到执行结果
```

**写法二：有返回值提交 —— `submit(Callable)` / `submit(Runnable)`**

需要拿执行结果时用 `submit`：

```java
// 1) submit(Callable)：任务有返回值
Future<Integer> future = executor.submit(() -> {
    // 模拟耗时计算（或查库）
    return 100;
});
Integer result = future.get();      // 阻塞等待结果；任务异常会被包成 ExecutionException
System.out.println(result);         // 100

// 2) submit(Runnable)：也有 Future，但 get() 只能拿到 null（Runnable 本身没有返回值）
Future<?> f = executor.submit(() -> System.out.println("干完啦"));
Object nothing = f.get();           // null
```

`Future` 还支持超时和取消：

```java
future.get(1, TimeUnit.SECONDS);    // 超时控制：等不到就抛 TimeoutException
future.cancel(true);                // 取消任务
```

**一句话**：`execute` = 提交即忘（无返回值）；`submit` = 拿着 `Future` 等结果（有返回值，`get()` 会阻塞）。项目里重建任务属于“无返回值”用法。

| 对比 | execute | submit |
|---|---|---|
| 参数 | Runnable | Runnable / Callable |
| 返回值 | 无 | `Future` |
| 异常去向 | 线程池的未捕获异常处理器（默认打印堆栈） | 封装进 Future，**不 get 就被吞** |
| 适合 | 不关心结果、希望异常可见 | 需要结果（或拿 Future 做取消/超时） |

**项目用法点评**：CacheClient 用的是 `submit(Runnable)` 且从不 `get()`——任务里的 `RuntimeException` 实际被静默吞掉（好在 `finally` 保证了解锁）。更稳妥的两种改法：

```java
// 改法一：execute + 自己记日志（异常不丢）
CACHE_REBUILD_EXECUTOR.execute(() -> {
    try {
        Shop shop = getShopFromDb(id);   // 查数据库
        saveShopToCache(key, shop);       // 回写缓存
    } catch (Exception e) {
        log.error("缓存重建失败 key={}", key, e);   // 出错能看见
    } finally {
        unLock(lockKey);
    }
});
```

### 2.6 CompletableFuture（异步编排，项目未用）

```java
// 假设秒杀详情要聚合“店铺 + 实时库存 + 券信息”三个独立调用
CompletableFuture<Shop> shopF = CompletableFuture.supplyAsync(
        () -> shopService.getById(shopId), executor);
CompletableFuture<Integer> stockF = CompletableFuture.supplyAsync(
        () -> readStock(voucherId), executor);

CompletableFuture.allOf(shopF, stockF).join();
Shop shop = shopF.join();
int stock = stockF.join();
```

特点：

- `runAsync`（无返回）/ `supplyAsync`（有返回）；
- 编排：`thenApply / thenAccept / thenCompose / thenCombine / allOf / anyOf`；
- **默认用 `ForkJoinPool.commonPool()`——生产必须显式传自定义线程池**；
- 异常：`join()` 抛 `CompletionException`（非受检），`get()` 抛 `ExecutionException`（受检）。

**项目为什么没用**：秒杀详情的数据源是本机缓存（Caffeine）/ Redis / MySQL，逐级回源本身已很快；它们之间还有“缓存命中就不查下一级”的依赖关系，并不是三个独立的远程调用，强行并行反而增加连接池压力——**这是“为了不用而不用”的正确答案**。

### 2.7 Spring @Async（项目未用）

```java
@Async
public void sendNotice(Long userId) { ... }   // 需要 @EnableAsync + 线程池配置
```

坑：同类内部自调用会绕过代理、直接变成同步；默认线程池不受控。项目里需要异步的链路全部走 MQ（更可靠），所以没有使用。

### 2.8 创建方式总对比表

| 方式 | 返回值 | 受检异常 | 线程复用 | 项目里 |
|---|---|---|---|---|
| 继承 Thread | 无 | 不能 | 无（每次新建） | 未用 |
| 实现 Runnable | 无 | 不能（要自己包） | 取决于提交到哪里 | **用（submit 到线程池）** |
| Callable + FutureTask | 有（Future） | 能（get 时包成 ExecutionException） | 同上 | 未用 |
| 线程池 execute(Runnable) | 无 | 运行时异常可见（未捕获处理器） | 有 | 未用 |
| 线程池 submit | 有（Future） | 封装，不 get 会被吞 | 有 | **用** |
| CompletableFuture | 链式结果 | join 非受检 / get 受检 | 默认 ForkJoinPool 或传入 | 未用 |
| Spring @Async | Future/void | 封装 | Spring 线程池 | 未用 |

**怎么选**：

```text
只是后台干活、不关心结果       -> Runnable + 线程池 execute（异常可见）
要结果 / 要取消 / 要超时        -> Callable + submit，或 FutureTask
多个异步任务要编排（依赖/聚合）  -> CompletableFuture
需要异步的跨服务链路            -> 首选 MQ（持久化+重试），不是线程池
```

---

## 3. 返回值与异常——把区别说透

### 3.1 Runnable vs Callable（同一件事两种写法）

```java
// Runnable：无返回
Runnable r = () -> { doRebuild(id); };
executor.execute(r);

// Callable：有返回、可抛受检异常
Callable<Boolean> c = () -> { doRebuild(id); return true; };
Future<Boolean> f = executor.submit(c);
boolean ok = f.get();   // 想拿结果，就得阻塞等待
```

一句话：**Runnable 是“动作”，Callable 是“带结果的动作”**；Callable 只能交给线程池（`submit`）或包进 `FutureTask`。

### 3.2 execute vs submit（异常的去向是最大区别）

```text
execute(task)：
  任务抛异常 → 线程池的 UncaughtExceptionHandler 处理（默认打印堆栈）→ 线程被替换
submit(task) ：
  任务抛异常 → 封装进返回的 Future → 不调用 get() 就永远没人知道
```

这就是为什么“用了线程池但出错看不见”的经典原因。项目里 `submit` 不 `get` 的重建任务就属于这类，改进见 2.5。

### 3.3 Future.get vs CompletableFuture.join

```text
get()  ：受检异常（InterruptedException / ExecutionException），可中断（cancel(true)）；
join() ：非受检（CompletionException），链式编排里更顺手。
两者都会阻塞调用线程——不要在请求线程上随便调用。
```

### 3.4 三个“异常被吞”的真实场景

1. **submit 不 get**（本项目的重建任务就是）：异常静默。至少任务内部要 `catch + log`；
2. **execute + 任务内部 catch 了不处理**：异常消失；
3. **@Async void 方法**：异常不会传回调用方。

教训：**线程池任务必须自己负责异常可观测性**（日志/监控），否则等于埋雷。

---

## 4. NearGo 线程全景（结合代码）

### 4.1 唯一自建线程池：CACHE_REBUILD_EXECUTOR

代码位置两处（下面简化为示意）：

```java
// 1) 定义（CacheClient.java:183，简化示意）
private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

// 2) 使用（CacheClient.java:156-177，简化示意）
if (tryLock(lockKey)) {                          // 抢到锁的线程才重建
    CACHE_REBUILD_EXECUTOR.submit(() -> {        // 丢给线程池，请求线程不等
        try {
            Shop shop = getShopFromDb(id);       // 查数据库
            saveShopToCache(key, shop);           // 回写缓存（新过期时间）
        } finally {
            unLock(lockKey);                      // 成败都要解锁
        }
    });
}
return oldShop;   // 立刻返回旧值
```

逐点解读：

- 为什么需要线程池：查库 + 回写是几百毫秒的 IO，**不能让请求线程等**；丢给线程池异步做，请求线程直接返回旧值（逻辑过期策略的“可用性优先”）；
- 为什么固定 10 个线程：重建任务量小（只有抢到锁的线程会提交一次），10 个足够且能兜住小突发；固定线程数也防止了“重建风暴”把线程打爆；
- 为什么任务里 try/finally：**锁必须释放**，否则后续重建永远抢不到锁；
- 已知隐患（面试加分点）：
  1. `newFixedThreadPool` 内部是**无界队列**——任务堆积会 OOM；改成显式 `new ThreadPoolExecutor`（有界队列 + CallerRunsPolicy）；
  2. 线程没有命名（dump 里是 `pool-1-thread-x`）——自定义 ThreadFactory；
  3. `static final` 且无生命周期管理——生产可交给 Spring 的 `ThreadPoolTaskExecutor`；
  4. `submit` 不 `get` 导致异常被吞——任务内 `catch + log`。

### 4.2 框架自带的线程（不是项目代码，但要说得出）

| 来源 | 线程 | 作用 |
|---|---|---|
| Tomcat | 请求工作线程池（默认 max 200） | 每个 HTTP 请求一个线程 |
| RocketMQ | 消费线程池（`CONCURRENTLY`） | 并发消费下单/关单消息 |
| RocketMQ | 生产者发送线程 | syncSend 的底层发送与重试 |
| Redisson | Netty event loop + 看门狗定时器 | 锁的通信与续期 |
| XXL-JOB | JobThread（每个 JobHandler 一条） | 对账任务串行执行 |

> 面试说法：“业务线程池只有缓存重建这一个；其余都是框架自带的，我清楚它们各自负责什么。”

### 4.3 ThreadLocal：UserHolder（并发上下文工具）

```java
public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();
    public static void saveUser(UserDTO user) { tl.set(user); }
    public static UserDTO getUser() { return tl.get(); }
    public static void removeUser() { tl.remove(); }
}
```

拦截器里：请求进入 `saveUser`，请求结束（`afterCompletion`）必须 `removeUser()`。

> 不 remove 的后果：Tomcat 线程池复用线程时**上下文串线**（上一个用户串到下一个请求）+ ThreadLocalMap **内存泄漏**。这和“线程池复用线程”是配套知识点。

---

## 5. 哪些地方适合多线程、哪些不适合

**适合：**

```text
1. 缓存异步重建（项目已用）
2. 多个相互独立的远程调用（可引入 CompletableFuture）
3. 对账任务逐条处理若瓶颈在 RTT，可分片并行（当前数据量小，没做）
4. 批量通知 / 短信 / 非核心逻辑
```

**不适合盲目并发：**

```text
1. 同一个数据库事务里的多个写操作
2. 同一订单/同一用户上的强顺序业务
3. 共享可变状态很多的代码
4. MQ 已经异步解耦的地方再套一层线程
```

---

## 6. 并发安全数据结构（项目现状与扩展）

> 诚实说明：NearGo 的共享状态全部放在 **Redis**（Spring 单例 Bean 里没有可变共享字段），所以没用 `ConcurrentHashMap`、`LongAdder`。下面是“如果做本机统计”的正确写法。

### ConcurrentHashMap

```java
ConcurrentMap<String, AtomicInteger> hotKeyCounters = new ConcurrentHashMap<>();
hotKeyCounters.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
```

### LongAdder（高并发计数更适合）

```java
ConcurrentMap<String, LongAdder> counters = new ConcurrentHashMap<>();
counters.computeIfAbsent(api, ignored -> new LongAdder()).increment();
```

### BlockingQueue

```java
BlockingQueue<String> queue = new ArrayBlockingQueue<>(1000);
```

### CopyOnWriteArrayList

适合读多写少：监听器列表、配置快照、白名单。

> **为什么秒杀用 Redis 而不是这些？** 限流/库存/名单要跨实例共享，JVM 内并发容器只在单机有效。

---

## 7. 为什么项目不用 JVM 锁

`synchronized`：互斥 + 可见性；`volatile`：可见性 + 有序性（`count++` 仍非原子）；`ReentrantLock`：支持 `tryLock`、可中断、公平锁。

```java
private volatile boolean running = true;
```

**NearGo 为什么一处 JVM 锁都没写？**

```text
1. 多实例部署：synchronized 只锁得住当前 JVM
2. 秒杀资格判断：Redis + Lua 原子脚本替代锁
3. 订单落库并发：Redisson 分布式锁（lock:order:{userId}）+ DB 条件更新
4. 缓存重建：Redis SETNX 互斥锁（lock:shop:{id}）
```

一句话：**JVM 锁只解决单实例并发；跨实例需要 Redis 分布式锁、数据库唯一约束/乐观锁。**

---

## 8. RocketMQ Consumer 与多线程

不要在 Consumer 里手动开线程：

```java
// 反例：消费线程先返回（消息已 ACK），后台线程随后失败 → 消息被认为消费成功，丢失
public void onMessage(String message) {
    executor.submit(() -> handle(message));   // ❌ 立刻返回 = 消费成功
}
```

NearGo 的消费端是**同步处理**（方法执行完才返回/抛异常）：

```java
@Override
public void onMessage(String orderId) {
    boolean closed = voucherOrderService.closeTimeoutOrder(Long.valueOf(orderId));
    log.info("RocketMQ 延迟关单 orderId={}, closed={}", orderId, closed);
    // 抛异常 → 消费失败 → Broker 稍后重投（closeTimeoutOrder 幂等，不会重复释放）
}
```

提升吞吐交给 MQ 自身：消费线程池 + 消费组（`consumeMode = CONCURRENTLY`）。

---

## 9. RocketMQ 消费重试与幂等

RocketMQ 常见语义是 At-Least-Once：

```text
读取消息 → 业务处理 → 返回成功（ACK）/ 抛异常（重投）
```

消费端必须幂等。**NearGo 没有用去重表**，因为“事件 ID”天然存在——订单 ID：

```text
第一层：getById(orderId) —— 同一条消息重投，直接返回
第二层：count(userId + voucherId + status != 4) —— 同一人重复下单被拦住
第三层：Redisson lock:order:{userId} —— 两条消息并发穿过前两层
第四层：DB 条件扣减 stock > 0 —— 状态不一致时抛异常重试
```

如果业务没有天然幂等键（如“点赞事件”），才需要 Inbox 去重表。

---

## 10. 消息可靠投递：为什么不用 Outbox

Kafka 体系常见 Outbox 模式：

```text
业务事务 ├─ 写业务表 └─ 写 event_outbox → Relay → Kafka
```

**NearGo 为什么不用 Outbox？** 因为秒杀入口首先改的是 **Redis（Lua 预扣）**，不是本地数据库事务——Outbox 要求“业务状态和待发事件在同一个本地事务里”，而我们的“业务状态”在 Redis 里，Outbox 解决不了 Redis 与消息的一致性。

替代方案（项目实际做法）：

```text
Lua 预扣成功 → 写预扣流水（reservation）
            → syncSend 同步等 Broker 确认
               ├─ 失败 → 回滚脚本补偿
               └─ 假失败（网络超时）→ 交给对账兜底
XXL-JOB 每分钟扫描流水 → 未落库重投 / 已取消释放 / 正常结案
```

一句话：**Outbox 解决“MySQL 事务 × 消息”的原子性；我们解决“Redis 预扣 × 消息”的最终一致性——同步确认 + 失败补偿 + 定时对账三件套。**
