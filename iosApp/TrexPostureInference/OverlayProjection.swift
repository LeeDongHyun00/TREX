/// 분석 버퍼는 이미 회전됐다. 화면에서는 비율 맞춤과 전면 미러만 적용한다.
enum OverlayProjection {
    static func point(x: Double, y: Double, imageWidth: Double, imageHeight: Double,
                      viewWidth: Double, viewHeight: Double, mirrored: Bool) -> (x: Double, y: Double)? {
        guard [x, y, imageWidth, imageHeight, viewWidth, viewHeight].allSatisfy(\.isFinite),
              imageWidth > 0, imageHeight > 0, viewWidth > 0, viewHeight > 0 else { return nil }
        let scale = min(viewWidth / imageWidth, viewHeight / imageHeight)
        let width = imageWidth * scale, height = imageHeight * scale
        return ((viewWidth - width) / 2 + (mirrored ? 1 - x : x) * width,
                (viewHeight - height) / 2 + y * height)
    }
}
