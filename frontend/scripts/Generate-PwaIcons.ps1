param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Drawing

$iconDirectory = [System.IO.Path]::GetFullPath(
  (Join-Path $PSScriptRoot '..\public\icons')
)
[System.IO.Directory]::CreateDirectory($iconDirectory) | Out-Null

function New-PersonalMemoIcon {
  param(
    [Parameter(Mandatory)] [int] $Size,
    [Parameter(Mandatory)] [string] $OutputPath
  )

  $scale = $Size / 512.0
  $bitmap = [System.Drawing.Bitmap]::new($Size, $Size)
  $graphics = [System.Drawing.Graphics]::FromImage($bitmap)

  try {
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.Clear([System.Drawing.ColorTranslator]::FromHtml('#17221c'))

    $page = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $page.AddPolygon(@(
      [System.Drawing.PointF]::new(142 * $scale, 128 * $scale),
      [System.Drawing.PointF]::new(310 * $scale, 128 * $scale),
      [System.Drawing.PointF]::new(370 * $scale, 188 * $scale),
      [System.Drawing.PointF]::new(370 * $scale, 384 * $scale),
      [System.Drawing.PointF]::new(142 * $scale, 384 * $scale)
    ))

    $paperBrush = [System.Drawing.SolidBrush]::new(
      [System.Drawing.ColorTranslator]::FromHtml('#f6f2e8')
    )
    $paperPen = [System.Drawing.Pen]::new($paperBrush.Color, 18 * $scale)
    $paperPen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
    $graphics.FillPath($paperBrush, $page)
    $graphics.DrawPath($paperPen, $page)

    $accent = [System.Drawing.ColorTranslator]::FromHtml('#d56a43')
    $accentPen = [System.Drawing.Pen]::new($accent, 18 * $scale)
    $accentPen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
    $graphics.DrawLines($accentPen, @(
      [System.Drawing.PointF]::new(310 * $scale, 128 * $scale),
      [System.Drawing.PointF]::new(310 * $scale, 188 * $scale),
      [System.Drawing.PointF]::new(370 * $scale, 188 * $scale)
    ))

    $inkPen = [System.Drawing.Pen]::new(
      [System.Drawing.ColorTranslator]::FromHtml('#17221c'),
      20 * $scale
    )
    $inkPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $inkPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    foreach ($line in @(
      @(194, 244, 318, 244),
      @(194, 292, 318, 292),
      @(194, 340, 272, 340)
    )) {
      $graphics.DrawLine(
        $inkPen,
        $line[0] * $scale,
        $line[1] * $scale,
        $line[2] * $scale,
        $line[3] * $scale
      )
    }

    $accentBrush = [System.Drawing.SolidBrush]::new($accent)
    $graphics.FillEllipse(
      $accentBrush,
      296 * $scale,
      300 * $scale,
      84 * $scale,
      84 * $scale
    )

    $checkPen = [System.Drawing.Pen]::new([System.Drawing.Color]::White, 12 * $scale)
    $checkPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $checkPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $checkPen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
    $graphics.DrawLines($checkPen, @(
      [System.Drawing.PointF]::new(320 * $scale, 342 * $scale),
      [System.Drawing.PointF]::new(333 * $scale, 355 * $scale),
      [System.Drawing.PointF]::new(358 * $scale, 327 * $scale)
    ))

    $bitmap.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png)
  }
  finally {
    foreach ($resource in @(
      $checkPen,
      $accentBrush,
      $inkPen,
      $accentPen,
      $paperPen,
      $paperBrush,
      $page,
      $graphics,
      $bitmap
    )) {
      if ($null -ne $resource) {
        $resource.Dispose()
      }
    }
  }
}

New-PersonalMemoIcon -Size 192 -OutputPath (Join-Path $iconDirectory 'icon-192.png')
New-PersonalMemoIcon -Size 512 -OutputPath (Join-Path $iconDirectory 'icon-512.png')
