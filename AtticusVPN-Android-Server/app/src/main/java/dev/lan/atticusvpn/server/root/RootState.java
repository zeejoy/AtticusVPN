/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.root;

/** Root 环境与实际授权分开记录；文件存在不等于本应用已取得 UID 0。 */
public enum RootState {
    ROOT_GRANTED("已授权（已验证 uid=0）"),
    ROOT_PERMISSION_DENIED("授权被拒绝"),
    ROOT_NOT_FOUND("未发现可执行 su（也可能被隐藏）"),
    ROOT_ERROR("检查失败；不等于未 Root"),
    ROOT_UNKNOWN("尚未验证授权");

    private final String message;
    RootState(String message) { this.message = message; }
    public String message() { return message; }
}
