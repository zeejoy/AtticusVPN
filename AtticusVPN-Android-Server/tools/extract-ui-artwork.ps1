# Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0
# 用户明确选择本地像素抠图；读取原 PNG，不重新生成、重画字体或覆盖原附件。
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$WordmarkImage,
    [Parameter(Mandatory=$true)][string]$SettingsImage,
    [Parameter(Mandatory=$true)][string]$ServerImage,
    [Parameter(Mandatory=$true)][string]$PeersImage,
    [Parameter(Mandatory=$true)][string]$ConnectionImage,
    [string]$ResourceRoot = (Join-Path (Split-Path -Parent $PSScriptRoot) 'app/src/main/res')
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$taskArtworkCode = @'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

public static class AtticusArtworkExtractor {
    private static int Clamp(int n) { return Math.Max(0, Math.Min(255, n)); }

    // mode 0: 原文字区白底；1: 绿底黑齿轮；2: 白底黑线图标。
    public static void Extract(string input, string output, string nightOutput, int mode) {
        using (var source = new Bitmap(input)) {
            if (mode == 0 && (source.Width != 1254 || source.Height != 1254))
                throw new ArgumentException("Wordmark source must match the supplied 1254x1254 original");
            var crop = mode == 0 ? new Rectangle(80, 900, 1100, 200)
                : new Rectangle(0, 0, source.Width, source.Height);
            using (var image = source.Clone(crop, PixelFormat.Format32bppArgb)) {
                var region = new Rectangle(0, 0, image.Width, image.Height);
                var data = image.LockBits(region, ImageLockMode.ReadWrite, PixelFormat.Format32bppArgb);
                var bytes = new byte[data.Stride * image.Height];
                int left = image.Width, top = image.Height, right = -1, bottom = -1;
                try {
                    Marshal.Copy(data.Scan0, bytes, 0, bytes.Length);
                    for (int y = 0; y < image.Height; y++) for (int x = 0; x < image.Width; x++) {
                        int p = y * data.Stride + x * 4;
                        int b = bytes[p], g = bytes[p+1], r = bytes[p+2], alpha;
                        if (mode == 0) {
                            // 只保留原图的深蓝/蓝色字样，排除白底纹理和中性灰阴影。
                            bool colored = b > r + 8 && Math.Max(b, g) - r >= 8 && r < 240;
                            alpha = colored ? (r < 64 ? 255 : 255-r) : 0;
                            if (alpha > 0 && alpha < 255) {
                                // 从白色底图反算抗锯齿边缘颜色，避免白色光晕。
                                r = 0;
                                g = Clamp((g-(255-alpha))*255/alpha);
                                b = Clamp((b-(255-alpha))*255/alpha);
                            }
                        } else {
                            alpha = mode == 1 ? 255-g : 255-(r+g+b)/3;
                            if (alpha < 18) alpha = 0;
                            r = g = b = 0;
                        }
                        bytes[p] = (byte)b; bytes[p+1] = (byte)g; bytes[p+2] = (byte)r; bytes[p+3] = (byte)alpha;
                        if (alpha >= 16) { left = Math.Min(left,x); top = Math.Min(top,y); right = Math.Max(right,x); bottom = Math.Max(bottom,y); }
                    }
                    Marshal.Copy(bytes, 0, data.Scan0, bytes.Length);
                } finally { image.UnlockBits(data); }
                if (right < left) throw new ArgumentException("No foreground extracted");
                var bounds = new Rectangle(Math.Max(0,left-2), Math.Max(0,top-2),
                    Math.Min(image.Width-1,right+2)-Math.Max(0,left-2)+1,
                    Math.Min(image.Height-1,bottom+2)-Math.Max(0,top-2)+1);
                using (var trimmed = image.Clone(bounds, PixelFormat.Format32bppArgb)) {
                    trimmed.Save(output, ImageFormat.Png);
                    if (mode == 0) {
                        // 使用同一份轮廓，仅换 Atticus 的颜色；VPN 渐变和 alpha 完全不变。
                        var rect = new Rectangle(0,0,trimmed.Width,trimmed.Height);
                        var nightData = trimmed.LockBits(rect, ImageLockMode.ReadWrite, PixelFormat.Format32bppArgb);
                        try {
                            var night = new byte[nightData.Stride*trimmed.Height];
                            Marshal.Copy(nightData.Scan0, night, 0, night.Length);
                            for(int y=0;y<trimmed.Height;y++) for(int x=0;x<trimmed.Width;x++) {
                                int p=y*nightData.Stride+x*4;
                                // Atticus 是深蓝，VPN 的蓝通道始终明亮；不按猜测的字体重新排字。
                                if(night[p+3] > 0 && night[p] < 160) night[p]=night[p+1]=night[p+2]=255;
                            }
                            Marshal.Copy(night,0,nightData.Scan0,night.Length);
                        } finally { trimmed.UnlockBits(nightData); }
                        trimmed.Save(nightOutput, ImageFormat.Png);
                    }
                }
            }
        }
    }
}
'@
if (-not ('AtticusArtworkExtractor' -as [type])) {
    $taskDrawingReferences = @(
        [System.Drawing.Bitmap].Assembly.Location,
        [System.Drawing.Rectangle].Assembly.Location
    ) + @(Get-ChildItem -LiteralPath $PSHOME -Filter 'System.Private.Windows*.dll' |
        ForEach-Object FullName)
    Add-Type -TypeDefinition $taskArtworkCode -ReferencedAssemblies $taskDrawingReferences
}
$taskNormal = Join-Path $ResourceRoot 'drawable-nodpi'
$taskNight = Join-Path $ResourceRoot 'drawable-night-nodpi'
New-Item -ItemType Directory -Path $taskNormal,$taskNight -Force | Out-Null
[AtticusArtworkExtractor]::Extract($WordmarkImage,(Join-Path $taskNormal 'atticus_wordmark.png'),(Join-Path $taskNight 'atticus_wordmark.png'),0)
[AtticusArtworkExtractor]::Extract($SettingsImage,(Join-Path $taskNormal 'atticus_settings.png'),'',1)
[AtticusArtworkExtractor]::Extract($ServerImage,(Join-Path $taskNormal 'atticus_page_server.png'),'',2)
[AtticusArtworkExtractor]::Extract($PeersImage,(Join-Path $taskNormal 'atticus_page_peers.png'),'',2)
[AtticusArtworkExtractor]::Extract($ConnectionImage,(Join-Path $taskNormal 'atticus_page_connection.png'),'',2)
Write-Output 'Extracted six transparent UI assets from the original images; originals unchanged.'
