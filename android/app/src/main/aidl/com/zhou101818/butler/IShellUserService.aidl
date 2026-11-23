package com.zhou101818.butler;

interface IShellUserService {
    String exec(String cmd);

    void exit();
}