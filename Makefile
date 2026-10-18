# 推演系统构建链（严格依赖顺序：JSON 编译 -> gcc C -> javac Java -> run）
#
#   JSON ──python compile──> build/logic/*.json ─┐
#   C 源码 ──gcc──> build/main-world             ├─> java -cp build/classes world.Orchestrator
#   Java 源码 ──javac──> build/classes/...        ┘
#
# 用法：
#   make              # 编译全部（逻辑 / C / Java）
#   make run ARGS="--max-ticks 200 --seed 42"
#   make run ARGS="--seeds 100 --max-ticks 200"
#   make clean

PYTHON   ?= python3.11
JAVAC    ?= javac
JAVA     ?= java
GCC      ?= gcc

LOGIC_SRC := logic
LOGIC_OUT := build/logic
C_SRC     := src/c/world.c
C_BIN     := build/main-world
JAVA_SRC  := src/java/world
JAVA_OUT  := build/classes

# 逻辑源码文件列表（含新增神时自动纳入先决条件）
LOGIC_JSON := $(shell find $(LOGIC_SRC) -name '*.json' 2>/dev/null)

.PHONY: all run clean

all: $(LOGIC_OUT)/.stamp $(C_BIN) $(JAVA_OUT)/world/Orchestrator.class

# 阶段一：编译逻辑（表达式 -> AST）。用 .stamp 跳过未变更。
$(LOGIC_OUT)/.stamp: $(LOGIC_JSON)
	$(PYTHON) python/compile.py --src $(LOGIC_SRC) --out $(LOGIC_OUT)
	@touch $@

# 阶段二：编译 C 世界状态机
$(C_BIN): $(C_SRC) src/c/jsmn.h
	$(GCC) -O2 -Wall -o $@ $(C_SRC) -lm

# 阶段三：编译 Java 编排层
$(JAVA_OUT)/world/Orchestrator.class: $(wildcard $(JAVA_SRC)/*.java)
	mkdir -p $(JAVA_OUT)
	$(JAVAC) -d $(JAVA_OUT) $(JAVA_SRC)/*.java

# 运行（先确保编译完成）
run: all
	$(JAVA) -cp $(JAVA_OUT) world.Orchestrator $(ARGS)

clean:
	rm -rf build/main-world build/classes $(LOGIC_OUT)/.stamp
	rm -f $(LOGIC_OUT)/world.json $(LOGIC_OUT)/manifest.json
	rm -f $(LOGIC_OUT)/gods/*.json
