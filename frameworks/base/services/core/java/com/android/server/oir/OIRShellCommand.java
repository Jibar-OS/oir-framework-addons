/*
 * Copyright (C) 2026 The OpenIntelligenceRuntime Project
 * Licensed under the Apache License, Version 2.0
 */
package com.android.server.oir;

import android.oir.IOIRService;
import android.oir.IOIRTokenCallback;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.util.Log;

import java.io.PrintWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 *   cmd oir submit "<prompt>"   Submit; stream tokens to stdout.
 *   cmd oir help                Help.
 */
public class OIRShellCommand extends ShellCommand {

    private static final String TAG = "OIRShellCommand";
    private static final long SUBMIT_TIMEOUT_SECONDS = 60L;

    private final OIRService mOwner;
    private final IOIRService.Stub mService;
    private final CapabilityRegistry mRegistry;

    public OIRShellCommand(OIRService owner, IOIRService.Stub service, CapabilityRegistry registry) {
        mOwner = owner;
        mService = service;
        mRegistry = registry;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) return handleDefaultCommands(null);
        switch (cmd) {
            case "submit":  return cmdSubmit();
            case "dumpsys": return cmdDumpsys();
            case "memory":  return cmdDumpsysMemory();
            case "warm":    return cmdWarm();
            case "embed":   return cmdEmbed();
            case "transcribe": return cmdTranscribe();
            case "synthesize": return cmdSynthesize();
            case "detect":  return cmdDetect();
            case "vembed":  return cmdVembed();
            case "describe":return cmdDescribe();
            case "vad":     return cmdVad();
            // v0.6.3: smoke-test subcommands for the four new v0.6 caps.
            case "classify": return cmdClassify();
            case "rerank":   return cmdRerank();
            case "translate":return cmdTranslate();
            case "ocr":      return cmdOcr();
            case "help":
            case "-h":
            case "--help": onHelp(); return 0;
            default: return handleDefaultCommands(cmd);
        }
    }

    private int cmdSubmit() {
        final PrintWriter pw = getOutPrintWriter();
        final PrintWriter errPw = getErrPrintWriter();

        int asUid = 0;
        String capability = "text.complete";
        String arg;
        while ((arg = peekNextArg()) != null && arg.startsWith("--")) {
            getNextArg();
            switch (arg) {
                case "--as-uid":
                    try { asUid = Integer.parseInt(getNextArg()); }
                    catch (NumberFormatException e) { errPw.println("--as-uid requires integer"); return 1; }
                    break;
                case "--cap":
                    capability = getNextArg();
                    break;
                default:
                    errPw.println("unknown flag: " + arg);
                    return 1;
            }
        }

        String prompt = getNextArg();
        if (prompt == null) {
            errPw.println("usage: cmd oir submit [--as-uid <uid>] [--cap <capability>] \"<prompt>\"");
            return 1;
        }

        final CountDownLatch done = new CountDownLatch(1);
        final int[] exitCode = {0};

        IOIRTokenCallback.Stub cb = new IOIRTokenCallback.Stub() {
            @Override public void onStart(Bundle meta) {
                pw.println("--- start handle=" + meta.getLong("handle")
                        + " backend=" + meta.getString("backend"));
                pw.flush();
            }
            @Override public void onToken(String token, int outputIndex) { pw.print(token); pw.flush(); }
            @Override public void onComplete(Bundle stats) {
                pw.println();
                pw.println("--- done tokens=" + stats.getInt("totalTokens")
                        + " firstTokenMs=" + stats.getLong("firstTokenMs")
                        + " totalMs=" + stats.getLong("totalMs"));
                pw.flush();
                done.countDown();
            }
            @Override public void onError(int errorCode, String message) {
                pw.println();
                errPw.println("--- error code=" + errorCode + " message=" + message);
                errPw.flush();
                exitCode[0] = 1;
                done.countDown();
            }
        };

        try {
            if (asUid > 0) {
                mOwner.submitAs(asUid, capability, prompt, new Bundle(), cb, null);
            } else {
                mService.submit(capability, prompt, new Bundle(), cb, null);
            }
            if (!done.await(SUBMIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                errPw.println("--- error timeout after " + SUBMIT_TIMEOUT_SECONDS + "s");
                return 1;
            }
        } catch (android.os.RemoteException | InterruptedException e) {
            errPw.println("--- error " + e.getMessage());
            return 1;
        }
        return exitCode[0];
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("OpenIntelligenceRuntime (OIR) commands:");
        pw.println();
        pw.println("  cmd oir submit \"<prompt>\"    Submit a prompt; stream response.");
        pw.println("  cmd oir embed <text>           text.embed → pooled vector.");
        pw.println("  cmd oir classify <text>        text.classify → per-label scores.");
        pw.println("  cmd oir rerank <q> <c1> [..]   text.rerank → score per candidate.");
        pw.println("  cmd oir translate [--src/--tgt] <text>");
        pw.println("                                   text.translate → stream translation.");
        pw.println("  cmd oir transcribe <wav>       audio.transcribe → stream segments.");
        pw.println("  cmd oir synthesize <text>      audio.synthesize → stream PCM chunks.");
        pw.println("  cmd oir vad <pcm>              audio.vad → stream voice-on/off edges.");
        pw.println("  cmd oir describe <img> [prompt] vision.describe → stream VLM caption.");
        pw.println("  cmd oir detect <img>           vision.detect → bounding boxes.");
        pw.println("  cmd oir ocr <img>              vision.ocr → bboxes with recognized text.");
        pw.println("  cmd oir vembed <img>           vision.embed → pooled vector.");
        pw.println("  cmd oir warm <capability>      lazy-load the default model for <cap>.");
        pw.println("  cmd oir dumpsys capabilities   List declared caps + runnability status.");
        pw.println("  cmd oir dumpsys config         Show resolved global + per-capability knobs.");
        pw.println("  cmd oir memory                 Runtime memory snapshot.");
        pw.println("  cmd oir help                   This help.");
    }

    private int cmdDumpsys() {
        final PrintWriter pw = getOutPrintWriter();
        String target = getNextArg();
        if ("config".equals(target)) {
            return cmdDumpsysConfig();
        }
        if (target == null || "capabilities".equals(target)) {
            CapabilityRegistry reg = mRegistry;
            java.util.Collection<Capability> all = reg.all();
            int runnable = 0, noModel = 0, missing = 0;
            pw.println("OIR capabilities (" + all.size() + " total):");
            for (Capability c : all) {
                // Delegate to IOIRService.isCapabilityRunnable so this shell
                // matches the production runnability contract:
                //   - splits pipe-delimited VLM paths (clip|llm); a bare
                //     File.isFile("a|b") would always say MODEL_MISSING
                //     because "a|b" isn't a real path.
                //   - falls back to the worker's `oird` SELinux domain
                //     via fileIsReadable() when system_server can't stat
                //     the file directly (non-cuttlefish products without
                //     the v0.6.1 sepolicy generalization).
                // Return codes: 0=UNKNOWN, 1=RUNNABLE, 2=NO_DEFAULT_MODEL,
                // 3=MODEL_MISSING. 0 should not happen here because we're
                // iterating the registry — include it for completeness.
                int code;
                try {
                    code = mService.isCapabilityRunnable(c.name);
                } catch (RemoteException e) {
                    Log.w(TAG, "isCapabilityRunnable(" + c.name + ") failed", e);
                    code = 0;
                }
                final String status;
                switch (code) {
                    case 1: status = "RUNNABLE";         runnable++; break;
                    case 2: status = "NO_DEFAULT_MODEL"; noModel++;  break;
                    case 3: status = "MODEL_MISSING";    missing++;  break;
                    default: status = "UNKNOWN";                     break;
                }
                pw.println("  " + c.name + "  [" + status + "]");
                pw.println("      shape:    " + c.shape);
                pw.println("      perm:     " + c.requiredPermission);
                pw.println("      model:    " + (c.defaultModelPath == null || c.defaultModelPath.isEmpty()
                        ? "(none — OEM must bake)"
                        : c.defaultModelPath));
                pw.println("      backend:  " + c.backend);
                pw.println("      source:   " + c.source);
            }
            pw.println("");
            pw.println("Summary: " + runnable + " runnable, "
                    + noModel + " unbacked, "
                    + missing + " model-missing");
            return 0;
        }
        getErrPrintWriter().println("unknown dumpsys target: " + target);
        return 1;
    }

    private int cmdDumpsysConfig() {
        final PrintWriter pw = getOutPrintWriter();
        OirConfig c = mOwner.getOirConfig();
        pw.println("OIR config (resolved from /system_ext/etc/oir/oir_config.xml + /vendor/etc/oir/oir_config.xml):");
        pw.println("");
        pw.println("Global knobs:");
        pw.println("  memory_budget_mb            = " + c.getMemoryBudgetMb());
        pw.println("  warm_ttl_seconds            = " + c.getWarmTtlSeconds());
        pw.println("  inference_timeout_seconds   = " + c.getInferenceTimeoutSecs());
        pw.println("  rate_limit_per_minute       = " + c.getRateLimitPerMinute());
        pw.println("  rate_limit_burst            = " + c.getRateLimitBurst());
        pw.println("");
        java.util.Map<String,Float> num = c.getCapabilityTuning();
        java.util.Map<String,String> str = c.getCapabilityTuningStrings();
        if (num.isEmpty() && str.isEmpty()) {
            pw.println("Capability tuning: <none — all defaults>");
        } else {
            pw.println("Capability tuning (" + (num.size() + str.size()) + " overrides):");
            for (java.util.Map.Entry<String,Float> e : new java.util.TreeMap<>(num).entrySet()) {
                pw.println("  " + e.getKey() + " = " + e.getValue());
            }
            for (java.util.Map.Entry<String,String> e : new java.util.TreeMap<>(str).entrySet()) {
                pw.println("  " + e.getKey() + " = \"" + e.getValue() + "\"");
            }
        }
        return 0;
    }

    private int cmdWarm() {
        final PrintWriter pw = getOutPrintWriter();
        final PrintWriter errPw = getErrPrintWriter();
        String cap = getNextArg();
        if (cap == null) {
            errPw.println("usage: cmd oir warm <capability>");
            return 1;
        }
        try {
            mService.warm(cap);
            pw.println("warmed: " + cap);
            return 0;
        } catch (android.os.RemoteException e) {
            errPw.println("warm failed: " + e.getMessage());
            return 1;
        } catch (IllegalArgumentException | SecurityException e) {
            errPw.println("warm failed: " + e.getMessage());
            return 1;
        }
    }

    private int cmdEmbed() {
        final PrintWriter pw = getOutPrintWriter();
        final PrintWriter errPw = getErrPrintWriter();
        String text = getNextArg();
        if (text == null || text.isEmpty()) {
            errPw.println("usage: cmd oir embed <text>");
            return 1;
        }
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final float[][] result = {null};
        final String[] error = {null};
        android.oir.IOIRVectorCallback cb = new android.oir.IOIRVectorCallback.Stub() {
            @Override public void onVector(float[] vec) { result[0] = vec; latch.countDown(); }
            @Override public void onError(int code, String msg) { error[0] = "err " + code + ": " + msg; latch.countDown(); }
        };
        try {
            mService.submitEmbedText("text.embed", text, cb, null);
            if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                errPw.println("embed timeout");
                return 1;
            }
            if (error[0] != null) { errPw.println(error[0]); return 1; }
            float[] v = result[0];
            pw.printf("embedding dim=%d L2=1.0 (expected for MiniLM)%n", v.length);
            // print first 8 + last 4 for eyeball
            StringBuilder sb = new StringBuilder("  [");
            for (int i = 0; i < Math.min(8, v.length); i++) sb.append(String.format("%.4f ", v[i]));
            if (v.length > 12) sb.append("... ");
            for (int i = Math.max(8, v.length - 4); i < v.length; i++) sb.append(String.format("%.4f ", v[i]));
            sb.append("]");
            pw.println(sb);
            return 0;
        } catch (android.os.RemoteException e) {
            errPw.println("embed failed: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errPw.println("embed interrupted");
            return 1;
        }
    }

    private int cmdTranscribe() {
        final PrintWriter pw = getOutPrintWriter();
        final PrintWriter errPw = getErrPrintWriter();
        String path = getNextArg();
        if (path == null || path.isEmpty()) {
            errPw.println("usage: cmd oir transcribe <wav-path> (16-bit mono 16 kHz)");
            return 1;
        }
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final String[] err = {null};
        android.oir.IOIRTokenCallback cb = new android.oir.IOIRTokenCallback.Stub() {
            @Override public void onStart(android.os.Bundle meta) {
                pw.println("--- transcribe start backend=" + meta.getString("backend"));
            }
            @Override public void onToken(String token, int outputIndex) {
                pw.print(token);
                pw.flush();
            }
            @Override public void onComplete(android.os.Bundle stats) {
                pw.println();
                pw.printf("--- done segments=%d wall_ms=%d%n",
                        stats.getInt("totalTokens", 0), stats.getLong("totalMs", 0));
                latch.countDown();
            }
            @Override public void onError(int code, String msg) {
                err[0] = "err " + code + ": " + msg;
                latch.countDown();
            }
        };
        try {
            mService.submit("audio.transcribe", path, new android.os.Bundle(), cb, null);
            if (!latch.await(120, java.util.concurrent.TimeUnit.SECONDS)) {
                errPw.println("transcribe timeout");
                return 1;
            }
            if (err[0] != null) { errPw.println(err[0]); return 1; }
            return 0;
        } catch (android.os.RemoteException e) {
            errPw.println("transcribe failed: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    private int cmdSynthesize() {
        final PrintWriter pw = getOutPrintWriter();
        final PrintWriter errPw = getErrPrintWriter();
        String text = getNextArg();
        String outPath = getNextArg();
        if (text == null || text.isEmpty()) {
            errPw.println("usage: cmd oir synthesize \"<text>\" [out.raw]");
            return 1;
        }
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final String[] err = {null};
        final int[] rateRef = {0};
        final java.util.List<byte[]> chunks = new java.util.ArrayList<>();
        android.oir.IOIRAudioStreamCallback cb = new android.oir.IOIRAudioStreamCallback.Stub() {
            @Override public void onChunk(byte[] pcm, int sampleRateHz, int channelCount, int encoding, boolean last) {
                rateRef[0] = sampleRateHz;
                chunks.add(pcm);
                pw.printf("  chunk bytes=%d rate=%d ch=%d enc=%d last=%s%n",
                        pcm.length, sampleRateHz, channelCount, encoding, last);
            }
            @Override public void onComplete(long totalMs) {
                pw.printf("--- done wall_ms=%d chunks=%d rate=%d%n", totalMs, chunks.size(), rateRef[0]);
                latch.countDown();
            }
            @Override public void onError(int code, String msg) {
                err[0] = "err " + code + ": " + msg;
                latch.countDown();
            }
        };
        try {
            mService.submitSynthesize("audio.synthesize", text, cb, null);
            if (!latch.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
                errPw.println("synthesize timeout");
                return 1;
            }
            if (err[0] != null) { errPw.println(err[0]); return 1; }
            if (outPath != null && !chunks.isEmpty()) {
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outPath)) {
                    for (byte[] c : chunks) fos.write(c);
                    pw.println("wrote " + outPath);
                } catch (java.io.IOException e) { errPw.println("write failed: " + e.getMessage()); }
            }
            return 0;
        } catch (android.os.RemoteException e) {
            errPw.println("synthesize failed: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    private int cmdDetect() {
        final PrintWriter pw = getOutPrintWriter();
        final PrintWriter errPw = getErrPrintWriter();
        String path = getNextArg();
        if (path == null || path.isEmpty()) {
            errPw.println("usage: cmd oir detect <image-path>");
            return 1;
        }
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final String[] err = {null};
        android.oir.IOIRBoundingBoxCallback cb = new android.oir.IOIRBoundingBoxCallback.Stub() {
            @Override public void onBoundingBoxes(java.util.List<android.oir.BoundingBox> boxes) {
                pw.printf("detected %d boxes%n", boxes.size());
                int i = 0;
                for (android.oir.BoundingBox b : boxes) {
                    String label = (b.labels != null && b.labels.length > 0) ? b.labels[0] : "?";
                    float score = (b.scores != null && b.scores.length > 0) ? b.scores[0] : 0f;
                    pw.printf("  [%d] x=%d y=%d w=%d h=%d %s %.3f%n",
                            i++, b.x, b.y, b.width, b.height, label, score);
                }
                latch.countDown();
            }
            @Override public void onError(int code, String msg) {
                err[0] = "err " + code + ": " + msg;
                latch.countDown();
            }
        };
        try {
            mService.submitDetect("vision.detect", path, cb, null);
            if (!latch.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
                errPw.println("detect timeout");
                return 1;
            }
            if (err[0] != null) { errPw.println(err[0]); return 1; }
            return 0;
        } catch (android.os.RemoteException e) {
            errPw.println("detect failed: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    /** v0.5 V5: audio.vad — run a PCM file through Silero, print boolean timeline. */
    private int cmdVad() {
        final PrintWriter pw = getOutPrintWriter();
        final PrintWriter errPw = getErrPrintWriter();
        String path = getNextArg();
        if (path == null || path.isEmpty()) {
            errPw.println("usage: cmd oir vad <pcm16le-16khz-mono-path>");
            return 1;
        }
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final String[] err = {null};
        final int[] windowCount = {0};
        final int[] voiceCount = {0};
        final boolean[] lastState = {false};
        android.oir.IOIRRealtimeBooleanCallback cb = new android.oir.IOIRRealtimeBooleanCallback.Stub() {
            @Override public void onState(boolean isTrue, long timestampMs) {
                windowCount[0]++;
                if (isTrue) voiceCount[0]++;
                if (isTrue != lastState[0]) {
                    pw.printf("%6d ms: %s%n", timestampMs, isTrue ? "VOICE" : "silence");
                    lastState[0] = isTrue;
                }
            }
            @Override public void onComplete() {
                pw.printf("done: %d windows, %d voice (%.1f%%)%n",
                        windowCount[0], voiceCount[0],
                        windowCount[0] > 0 ? (100.0 * voiceCount[0] / windowCount[0]) : 0.0);
                latch.countDown();
            }
            @Override public void onError(int code, String msg) {
                err[0] = "err " + code + ": " + msg;
                latch.countDown();
            }
        };
        try {
            mService.submitVad("audio.vad", path, cb, null);
            if (!latch.await(120, java.util.concurrent.TimeUnit.SECONDS)) {
                errPw.println("vad timeout");
                return 1;
            }
            if (err[0] != null) { errPw.println(err[0]); return 1; }
            return 0;
        } catch (android.os.RemoteException e) {
            errPw.println("vad failed: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    // v0.6.3 ------------------------------------------------------------
    // Shell subcommands for the four new v0.6 capabilities. The AIDL
    // surface + worker routing shipped in v0.6.0; the shell bindings were
    // missed — so callers couldn't smoke-test classify / rerank /
    // translate / ocr from `cmd oir` despite the runtime supporting them.
    // Closes the "consumer is cmd oir until v0.7" story.
    // -------------------------------------------------------------------

    private int cmdClassify() {
        final PrintWriter pw = getOutPrintWriter();
        final PrintWriter errPw = getErrPrintWriter();
        String text = getNextArg();
        if (text == null || text.isEmpty()) {
            errPw.println("usage: cmd oir classify <text>");
            return 1;
        }
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final float[][] result = {null};
        final String[] error = {null};
        android.oir.IOIRVectorCallback cb = new android.oir.IOIRVectorCallback.Stub() {
            @Override public void onVector(float[] vec) { result[0] = vec; latch.countDown(); }
            @Override public void onError(int code, String msg) { error[0] = "err " + code + ": " + msg; latch.countDown(); }
        };
        try {
            mService.submitEmbedText("text.classify", text, cb, null);
            if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                errPw.println("classify timeout"); return 1;
            }
            if (error[0] != null) { errPw.println(error[0]); return 1; }
            float[] v = result[0];
            pw.printf("scores (n=%d):%n", v.length);
            int topIdx = 0;
            for (int i = 1; i < v.length; i++) if (v[i] > v[topIdx]) topIdx = i;
            for (int i = 0; i < v.length; i++) {
                pw.printf("  label[%d] = %.4f%s%n", i, v[i], i == topIdx ? "  <- top" : "");
            }
            return 0;
        } catch (android.os.RemoteException e) {
            errPw.println("classify failed: " + e.getMessage()); return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return 1;
        }
    }

    private int cmdRerank() {
        final PrintWriter pw = getOutPrintWriter();
        final PrintWriter errPw = getErrPrintWriter();
        String query = getNextArg();
        if (query == null || query.isEmpty()) {
            errPw.println("usage: cmd oir rerank <query> <candidate1> [<candidate2> ...]");
            return 1;
        }
        java.util.List<String> candidates = new java.util.ArrayList<>();
        String next;
        while ((next = getNextArg()) != null) candidates.add(next);
        if (candidates.isEmpty()) {
            errPw.println("at least one candidate required");
            return 1;
        }
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final float[][] result = {null};
        final String[] error = {null};
        android.oir.IOIRVectorCallback cb = new android.oir.IOIRVectorCallback.Stub() {
            @Override public void onVector(float[] vec) { result[0] = vec; latch.countDown(); }
            @Override public void onError(int code, String msg) { error[0] = "err " + code + ": " + msg; latch.countDown(); }
        };
        try {
            mService.submitRerank("text.rerank", query,
                    candidates.toArray(new String[0]), cb, null);
            if (!latch.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
                errPw.println("rerank timeout"); return 1;
            }
            if (error[0] != null) { errPw.println(error[0]); return 1; }
            float[] scores = result[0];
            // Sort candidates by score descending for readability.
            Integer[] order = new Integer[scores.length];
            for (int i = 0; i < order.length; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> Float.compare(scores[b], scores[a]));
            pw.printf("rerank \"%s\" over %d candidates:%n", query, candidates.size());
            for (int i = 0; i < order.length; i++) {
                int idx = order[i];
                pw.printf("  %.4f  %s%n", scores[idx], candidates.get(idx));
            }
            return 0;
        } catch (android.os.RemoteException e) {
            errPw.println("rerank failed: " + e.getMessage()); return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return 1;
        }
    }

    private int cmdTranslate() {
        final PrintWriter pw = getOutPrintWriter();
        final PrintWriter errPw = getErrPrintWriter();
        String src = "auto";
        String tgt = "en";
        String text = null;
        String arg;
        while ((arg = getNextArg()) != null) {
            if ("--src".equals(arg)) { src = getNextArg(); }
            else if ("--tgt".equals(arg)) { tgt = getNextArg(); }
            else { text = arg; break; }
        }
        // Accept trailing tokens as the full text ("cmd oir translate --tgt fr Hello world").
        if (text != null) {
            StringBuilder sb = new StringBuilder(text);
            String rest;
            while ((rest = getNextArg()) != null) sb.append(' ').append(rest);
            text = sb.toString();
        }
        if (text == null || text.isEmpty() || src == null || tgt == null) {
            errPw.println("usage: cmd oir translate [--src <lang>] [--tgt <lang>] <text>");
            return 1;
        }
        android.os.Bundle opts = new android.os.Bundle();
        opts.putString("sourceLang", src);
        opts.putString("targetLang", tgt);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final String[] err = {null};
        android.oir.IOIRTokenCallback cb = new android.oir.IOIRTokenCallback.Stub() {
            @Override public void onStart(android.os.Bundle meta) {
                pw.println("--- translate start backend=" + meta.getString("backend"));
            }
            @Override public void onToken(String token, int outputIndex) { pw.print(token); pw.flush(); }
            @Override public void onComplete(android.os.Bundle stats) {
                pw.println();
                pw.printf("--- done tokens=%d wall_ms=%d%n",
                        stats.getInt("totalTokens", 0), stats.getLong("totalMs", 0));
                latch.countDown();
            }
            @Override public void onError(int code, String msg) {
                err[0] = "err " + code + ": " + msg; latch.countDown();
            }
        };
        try {
            mService.submit("text.translate", text, opts, cb, null);
            if (!latch.await(120, java.util.concurrent.TimeUnit.SECONDS)) {
                errPw.println("translate timeout"); return 1;
            }
            if (err[0] != null) { errPw.println(err[0]); return 1; }
            return 0;
        } catch (android.os.RemoteException e) {
            errPw.println("translate failed: " + e.getMessage()); return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return 1;
        }
    }

    private int cmdOcr() {
        final PrintWriter pw = getOutPrintWriter();
        final PrintWriter errPw = getErrPrintWriter();
        String path = getNextArg();
        if (path == null || path.isEmpty()) {
            errPw.println("usage: cmd oir ocr <image-path>  (.jpg/.png)");
            return 1;
        }
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final String[] err = {null};
        android.oir.IOIRBoundingBoxCallback cb = new android.oir.IOIRBoundingBoxCallback.Stub() {
            @Override public void onBoundingBoxes(java.util.List<android.oir.BoundingBox> boxes) {
                pw.printf("ocr: %d regions%n", boxes.size());
                int i = 0;
                for (android.oir.BoundingBox b : boxes) {
                    String label = (b.labels != null && b.labels.length > 0) ? b.labels[0] : "";
                    float score = (b.scores != null && b.scores.length > 0) ? b.scores[0] : 0f;
                    pw.printf("  [%4d,%4d %4dx%4d] %.3f  %s%n",
                            b.x, b.y, b.width, b.height, score, label);
                    i++;
                }
                latch.countDown();
            }
            @Override public void onError(int code, String msg) {
                err[0] = "err " + code + ": " + msg; latch.countDown();
            }
        };
        try {
            mService.submitDetect("vision.ocr", path, cb, null);
            if (!latch.await(120, java.util.concurrent.TimeUnit.SECONDS)) {
                errPw.println("ocr timeout"); return 1;
            }
            if (err[0] != null) { errPw.println(err[0]); return 1; }
            return 0;
        } catch (android.os.RemoteException e) {
            errPw.println("ocr failed: " + e.getMessage()); return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return 1;
        }
    }

    private int cmdVembed() {
        final PrintWriter pw = getOutPrintWriter();
        final PrintWriter errPw = getErrPrintWriter();
        String path = getNextArg();
        if (path == null || path.isEmpty()) {
            errPw.println("usage: cmd oir vembed <image-path>  (.jpg/.png)");
            return 1;
        }
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final float[][] result = {null};
        final String[] error = {null};
        android.oir.IOIRVectorCallback cb = new android.oir.IOIRVectorCallback.Stub() {
            @Override public void onVector(float[] vec) { result[0] = vec; latch.countDown(); }
            @Override public void onError(int code, String msg) { error[0] = "err " + code + ": " + msg; latch.countDown(); }
        };
        try {
            mService.submitEmbedText("vision.embed", path, cb, null);
            if (!latch.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
                errPw.println("vembed timeout"); return 1;
            }
            if (error[0] != null) { errPw.println(error[0]); return 1; }
            float[] v = result[0];
            pw.printf("image-embedding dim=%d L2=1.0%n", v.length);
            StringBuilder sb = new StringBuilder("  [");
            for (int i = 0; i < Math.min(8, v.length); i++) sb.append(String.format("%.4f ", v[i]));
            if (v.length > 12) sb.append("... ");
            for (int i = Math.max(8, v.length - 4); i < v.length; i++) sb.append(String.format("%.4f ", v[i]));
            sb.append("]");
            pw.println(sb);
            return 0;
        } catch (android.os.RemoteException e) {
            errPw.println("vembed failed: " + e.getMessage()); return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return 1;
        }
    }

    private int cmdDescribe() {
        final PrintWriter pw = getOutPrintWriter();
        final PrintWriter errPw = getErrPrintWriter();
        String path = getNextArg();
        if (path == null || path.isEmpty()) {
            errPw.println("usage: cmd oir describe <image-path> [\"<prompt>\"]");
            return 1;
        }
        String userPrompt = getNextArg();
        final String effective = (userPrompt != null && !userPrompt.isEmpty())
                ? path + " | " + userPrompt : path;
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final String[] err = {null};
        android.oir.IOIRTokenCallback cb = new android.oir.IOIRTokenCallback.Stub() {
            @Override public void onStart(android.os.Bundle meta) {
                pw.println("--- describe start backend=" + meta.getString("backend"));
                pw.flush();
            }
            @Override public void onToken(String token, int outputIndex) {
                pw.print(token); pw.flush();
            }
            @Override public void onComplete(android.os.Bundle stats) {
                pw.println();
                pw.printf("--- done tokens=%d wall_ms=%d%n",
                        stats.getInt("totalTokens", 0), stats.getLong("totalMs", 0));
                latch.countDown();
            }
            @Override public void onError(int code, String msg) {
                err[0] = "err " + code + ": " + msg;
                latch.countDown();
            }
        };
        try {
            mService.submit("vision.describe", effective, new android.os.Bundle(), cb, null);
            if (!latch.await(180, java.util.concurrent.TimeUnit.SECONDS)) {
                errPw.println("describe timeout"); return 1;
            }
            if (err[0] != null) { errPw.println(err[0]); return 1; }
            return 0;
        } catch (android.os.RemoteException e) {
            errPw.println("describe failed: " + e.getMessage()); return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return 1;
        }
    }

    private int cmdDumpsysMemory() {
        final PrintWriter pw = getOutPrintWriter();
        final PrintWriter errPw = getErrPrintWriter();
        try {
            com.android.server.oir.MemoryStats s = mOwner.getInternalMemoryStats();
            if (s == null) {
                errPw.println("worker not attached");
                return 1;
            }
            pw.printf("OIR memory state:%n");
            pw.printf("    models loaded:    %d%n", s.modelCount);
            pw.printf("    resident:         %d MB%n%n", s.residentMb);
            long now = System.currentTimeMillis();
            for (int i = 0; i < s.modelCount; i++) {
                long ageS = (now - s.loadTimestampMs[i]) / 1000;
                long lastS = (now - s.lastAccessMs[i]) / 1000;
                pw.printf("  %s%n", s.modelPaths[i]);
                pw.printf("      size:         %d MB%n", s.modelSizesMb[i]);
                pw.printf("      loaded:       %d s ago%n", ageS);
                pw.printf("      last_access:  %d s ago%n", lastS);
            }
            // v0.6.3: runtime pool snapshot. Augments the model-level stats
            // above with concurrency view (backend / pool_size / busy /
            // waiting). Source: IOirWorker.dumpRuntimeStats() — TSV per
            // loaded model.
            String tsv = mOwner.getInternalRuntimeStats();
            if (tsv != null && !tsv.isEmpty()) {
                pw.println();
                pw.println("  runtime pools:");
                pw.println("    handle  backend       pool  busy  wait   sizeMB  path");
                for (String line : tsv.split("\n")) {
                    if (line.isEmpty()) continue;
                    String[] cols = line.split("\t");
                    if (cols.length < 7) continue;
                    pw.printf("    %-6s  %-12s  %-4s  %-4s  %-4s   %-6s  %s%n",
                            cols[0], cols[1], cols[2], cols[3], cols[4],
                            cols[5], cols[6]);
                }
            }
            return 0;
        } catch (android.os.RemoteException e) {
            errPw.println("dumpsys memory failed: " + e.getMessage());
            return 1;
        }
    }

}
