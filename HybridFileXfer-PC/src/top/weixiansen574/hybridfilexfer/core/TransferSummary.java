package top.weixiansen574.hybridfilexfer.core;

import java.util.ArrayList;
import java.util.List;

public final class TransferSummary {
    public long completedFiles, skippedFiles, failedFiles, transferredBytes, resumedBytes;
    public boolean cancelled;
    public final List<String> failures = new ArrayList<>();
    public boolean isSuccessful() { return !cancelled && failedFiles == 0; }
}
