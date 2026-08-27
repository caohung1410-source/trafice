package vn.bachphuc.trafficai;

/** Hình học ghép hộp biển giữa các khung, tách khỏi Android để kiểm thử trên JVM. */
public final class SignTrackMath {
    private SignTrackMath() {
    }

    public static float affinity(
            float aLeft, float aTop, float aRight, float aBottom,
            float bLeft, float bTop, float bRight, float bBottom) {
        float aWidth = Math.max(1f, aRight - aLeft);
        float aHeight = Math.max(1f, aBottom - aTop);
        float bWidth = Math.max(1f, bRight - bLeft);
        float bHeight = Math.max(1f, bBottom - bTop);
        float left = Math.max(aLeft, bLeft);
        float top = Math.max(aTop, bTop);
        float right = Math.min(aRight, bRight);
        float bottom = Math.min(aBottom, bBottom);
        float intersection = Math.max(0f, right - left) * Math.max(0f, bottom - top);
        float union = aWidth * aHeight + bWidth * bHeight - intersection;
        float iou = intersection / Math.max(1e-5f, union);

        float aCenterX = (aLeft + aRight) * .5f;
        float aCenterY = (aTop + aBottom) * .5f;
        float bCenterX = (bLeft + bRight) * .5f;
        float bCenterY = (bTop + bBottom) * .5f;
        float diagonal = (float) Math.hypot(
                Math.max(aWidth, bWidth), Math.max(aHeight, bHeight));
        float allowedMotion = Math.max(48f, diagonal * 4.5f);
        float proximity = Math.max(0f, 1f - (float) Math.hypot(
                aCenterX - bCenterX, aCenterY - bCenterY) / allowedMotion);
        float widthRatio = Math.min(aWidth, bWidth) / Math.max(aWidth, bWidth);
        float heightRatio = Math.min(aHeight, bHeight) / Math.max(aHeight, bHeight);
        float size = (float) Math.sqrt(Math.max(0f, widthRatio * heightRatio));
        // Kích thước giống nhau không đủ để ghép hai biển ở xa thành một track.
        return Math.min(1f, iou * .50f + proximity * .40f + size * .10f);
    }
}
