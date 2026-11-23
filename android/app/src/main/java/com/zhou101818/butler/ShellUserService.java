package com.zhou101818.butler;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import android.os.RemoteException;

/**
 * 用户服务：运行在 Shizuku 服务进程内（shell uid），用于免 root 执行命令。
 */
public class ShellUserService implements IShellUserService {

    @Override
    public String exec(String cmd) throws RemoteException {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            StringBuilder out = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) out.append(line).append("\n");
            StringBuilder err = new StringBuilder();
            BufferedReader er = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            while ((line = er.readLine()) != null) err.append(line).append("\n");
            p.waitFor();
            String s = out.toString().trim();
            return s.isEmpty() ? err.toString().trim() : s;
        } catch (Throwable t) {
            return "ERROR: " + t;
        }
    }

    @Override
    public void exit() throws RemoteException {
        System.exit(0);
    }
}