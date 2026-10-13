#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 shade 进来的第三方库做包名重定位（等价于 maven-shade-plugin 的 <relocation>）。

用法:
    python relocate.py <解压目录> <旧包内名> <新包内名>
    python relocate.py build/sqlite org/sqlite pokeshaded

为什么需要它（本项目实测踩到的坑）:
    CatServer 1.12.2 的 libraries/ 目录里自带 sqlite-jdbc-3.21.0.1.jar，而 Bukkit 的
    PluginClassLoader 是「先委托父加载器」的。父加载器能解析 org.sqlite.SQLiteConfig，
    于是我们自己 shade 进来的 3.42 版类永远不会被用到 —— 运行期直接抛
        java.lang.NoSuchMethodError: org.sqlite.SQLiteConfig.setBusyTimeout(I)V
    （setBusyTimeout 是 3.24+ 才加的）。把包名挪到独立命名空间后才与外部 jar 彻底隔离。

实现要点（为什么可以直接做字节替换）:
    class 文件里所有类名都存放在常量池的 CONSTANT_Utf8 条目中，格式是
        tag(1) + length(2) + bytes[length]
    只要替换前后**长度相同**，length 字段就不需要改动，常量池结构完全不变，
    因此可以安全地做纯字节级替换，不必解析和重建常量池。
    本项目的重定位目标 "pokeshaded" 与下面三种形式一样都是 10 个字符，
    正是为了满足这个约束（下面会断言校验）。

三种需要替换的形式（缺一不可）:
    org/sqlite    内部名 —— class 文件里的类型引用、资源路径
    org.sqlite    点号名 —— 字符串常量里的类名（如 META-INF/services 的内容）
    org_sqlite    JNI 形式 —— native 库（.dll/.so）里导出的符号名。
                  JNI 的符号命名规则把包名的点换成下划线，于是 DLL 导出的是
                  Java_org_sqlite_core_NativeDB__1open_1utf8 这样的符号。
                  只改 Java 侧不改 native 侧，运行期就会抛
                  UnsatisfiedLinkError: pokeshaded.core.NativeDB._open_utf8([BI)V
                  —— 本项目实测正是这么栽的，因此 native 文件也要参与替换。
"""

import os
import sys


def relocate(root, old_name, new_name):
    """就地把目录下所有文件里的 old_name 替换成 new_name。返回被改动的文件数。"""
    if len(old_name) != len(new_name):
        raise SystemExit("包名必须等长（否则要重写常量池）: %r(%d) vs %r(%d)"
                         % (old_name, len(old_name), new_name, len(new_name)))

    old_dot = old_name.replace('/', '.')
    new_dot = new_name.replace('/', '.')
    old_jni = old_name.replace('/', '_')
    new_jni = new_name.replace('/', '_')
    for label, old_form, new_form in (("点号形式", old_dot, new_dot), ("JNI 形式", old_jni, new_jni)):
        if len(old_form) != len(new_form):
            raise SystemExit("%s也必须等长: %r vs %r" % (label, old_form, new_form))

    # 顺序无关：三种形式互不重叠（斜杠 / 点 / 下划线）
    old_forms = (old_name.encode('ascii'), old_dot.encode('ascii'), old_jni.encode('ascii'))
    new_forms = (new_name.encode('ascii'), new_dot.encode('ascii'), new_jni.encode('ascii'))

    touched = 0
    for dirpath, _dirnames, filenames in os.walk(root):
        for name in filenames:
            path = os.path.join(dirpath, name)
            with open(path, 'rb') as fh:
                data = fh.read()
            replaced = data
            for old_bytes, new_bytes in zip(old_forms, new_forms):
                if old_bytes in replaced:
                    replaced = replaced.replace(old_bytes, new_bytes)
            if replaced != data:
                with open(path, 'wb') as fh:
                    fh.write(replaced)
                touched += 1
    return touched


def move_package(root, old_name, new_name):
    """把包目录整体改名，并清掉因此空掉的父目录。"""
    src = os.path.join(root, *old_name.split('/'))
    dst = os.path.join(root, *new_name.split('/'))
    if not os.path.isdir(src):
        raise SystemExit("找不到待重定位的包目录: %s" % src)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    if os.path.exists(dst):
        raise SystemExit("目标包目录已存在: %s" % dst)
    os.rename(src, dst)

    parent = os.path.dirname(src)
    while os.path.abspath(parent) != os.path.abspath(root) and not os.listdir(parent):
        os.rmdir(parent)
        parent = os.path.dirname(parent)


def main():
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    root, old_name, new_name = sys.argv[1], sys.argv[2], sys.argv[3]
    count = relocate(root, old_name, new_name)
    move_package(root, old_name, new_name)
    print("    重定位 %s -> %s：改写 %d 个文件" % (old_name, new_name, count))


if __name__ == '__main__':
    main()
