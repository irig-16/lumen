package android.media.audiofx;

/**
 * Compile-time stub of the Android 9 (API 28) DynamicsProcessing API, because the build uses
 * the API 23 platform jar. It is NOT packaged into the APK: at runtime on Android 9+ the real
 * framework class is used. Lumen only touches it behind an SDK_INT >= 28 check, inside try/catch.
 */
public class DynamicsProcessing {
    public static final int VARIANT_FAVOR_FREQUENCY_RESOLUTION = 0;
    public static final int VARIANT_FAVOR_TIME_RESOLUTION = 1;

    public DynamicsProcessing(int priority, int audioSession, Config cfg) {
        throw new RuntimeException("stub");
    }

    public int setEnabled(boolean enabled) {
        throw new RuntimeException("stub");
    }

    public boolean hasControl() {
        throw new RuntimeException("stub");
    }

    public boolean getEnabled() {
        throw new RuntimeException("stub");
    }

    public void setControlStatusListener(android.media.audiofx.AudioEffect.OnControlStatusChangeListener l) {
        throw new RuntimeException("stub");
    }

    public void release() {
        throw new RuntimeException("stub");
    }

    public void setMbcBandAllChannelsTo(int band, MbcBand mbcBand) {
        throw new RuntimeException("stub");
    }

    public void setLimiterAllChannelsTo(Limiter limiter) {
        throw new RuntimeException("stub");
    }

    public void setPreEqBandAllChannelsTo(int band, EqBand preEqBand) {
        throw new RuntimeException("stub");
    }

    public void setInputGainAllChannelsTo(float inputGain) {
        throw new RuntimeException("stub");
    }

    public static final class Config {
        public static final class Builder {
            public Builder(int variant, int channelCount, boolean preEqInUse, int preEqBandCount,
                           boolean mbcInUse, int mbcBandCount, boolean postEqInUse, int postEqBandCount,
                           boolean limiterInUse) {
                throw new RuntimeException("stub");
            }

            public Config build() {
                throw new RuntimeException("stub");
            }
        }
    }

    public static final class EqBand {
        public EqBand(boolean enabled, float cutoffFrequency, float gain) {
            throw new RuntimeException("stub");
        }
    }

    public static final class MbcBand {
        public MbcBand(boolean enabled, float cutoffFrequency, float attackTime, float releaseTime, float ratio,
                       float threshold, float kneeWidth, float noiseGateThreshold, float expanderRatio,
                       float preGain, float postGain) {
            throw new RuntimeException("stub");
        }
    }

    public static final class Limiter {
        public Limiter(boolean inUse, boolean enabled, int linkGroup, float attackTime, float releaseTime,
                       float ratio, float threshold, float postGain) {
            throw new RuntimeException("stub");
        }
    }
}
