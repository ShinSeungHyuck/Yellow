package com.example.yellow

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.*

/**
 * Mp3OnsetDetectorV3 (recommended tuning)
 *
 * 핵심 변경점(권장 방식):
 * 1) "연속 N프레임" 대신 "최근 N프레임 중 M프레임 이상 hit" (HIT_RATIO)로 판정 → 조용한 유음도 잘 잡힘
 * 2) 에너지 임계치(ABS_DB_THRESHOLD) 완화 → -30dB 같은 빡센 기준 때문에 유음이 무음으로 떨어지는 문제 해결
 * 3) 큰 잡음은 noiseLike 게이트로 차단 (flatness/crest/topRatio/acPeak 기반)
 */
object Mp3OnsetDetectorV3 {

    // =========================
    // Tunables (edit here)
    // =========================

    /** 분석 구간(초): "N초 안에 확실한 음이 시작" 판정용 */
    @JvmField var ANALYZE_SEC = 4.0

    /** 프레임/홉 */
    @JvmField var FFT_SIZE = 2048
    @JvmField var HOP_SIZE = 512

    /** 연속 조건(최근 구간에서 hit 비율로 판정) */
    @JvmField var MIN_HIT_MS = 120          // 240은 조용한 곡에서 끊기기 쉬움
    @JvmField var HIT_RATIO = 0.70          // 최근 windowFrames 중 70% 이상 hit면 onset 인정

    /** 노이즈 바닥 추정 구간 */
    @JvmField var PRE_NOISE_EST_SEC = 0.6

    /** 에너지 기준 (dBFS) : "평범한" 수준으로 완화 */
    @JvmField var ABS_DB_THRESHOLD = -42.0        // (중요) -30 같은 값이면 조용한 유음이 다 탈락할 수 있음
    @JvmField var REL_DB_ABOVE_NOISE = 18.0       // 노이즈 바닥 대비 +dB

    /** 변화 감지 */
    @JvmField var MIN_FLUX = 0.10
    @JvmField var JUMP_DB = 6.0

    /** "톤(유음)" 판정용 특징들 (평범한 수준) */
    @JvmField var MAX_FLATNESS = 0.70             // 1에 가까울수록 잡음, 낮을수록 tonal
    @JvmField var MIN_AC_PEAK = 0.25              // 주기성
    @JvmField var MIN_CREST = 3.5                 // 스펙트럼 crest (max/mean, linear)
    @JvmField var MIN_TOP_RATIO = 0.24            // 상위 bin 에너지 집중도

    /** "큰 잡음이어도 무음으로" 게이트 (너무 공격적이면 유음도 잡음으로 몰릴 수 있어 완화) */
    @JvmField var NOISE_FLATNESS = 0.88
    @JvmField var NOISE_MAX_CREST = 4.0
    @JvmField var NOISE_MAX_TOP_RATIO = 0.25
    @JvmField var NOISE_MAX_AC_PEAK = 0.20

    /** 로그 */
    @JvmField var DEBUG = true
    private const val TAG = "Mp3OnsetDetectorV3"

    // =========================
    // Public API
    // =========================

    data class Result(
        val hasOnset: Boolean,
        val onsetSec: Double?,          // 발견된 시점(초)
        val noiseFloorDb: Double,
        val requiredDb: Double,
        val reason: String
    )

    fun analyze(context: Context, uri: Uri): Result {
        Log.e(TAG, "analyze() ENTER uri=$uri")

        val pcm = decodeFirstSecondsToMonoFloat(context, uri, ANALYZE_SEC) ?: run {
            Log.e(TAG, "decode_failed uri=$uri")
            return Result(false, null, -120.0, ABS_DB_THRESHOLD, "decode_failed")
        }

        val x = pcm.samples
        val sampleRate = pcm.sampleRate
        if (x.isEmpty()) {
            Log.e(TAG, "empty_pcm uri=$uri")
            return Result(false, null, -120.0, ABS_DB_THRESHOLD, "empty_pcm")
        }

        val fftSize = FFT_SIZE
        val hop = HOP_SIZE
        val window = hannWindow(fftSize)

        // --- Noise floor estimation (robust) ---
        val maxNoiseSamples = min(x.size, (PRE_NOISE_EST_SEC * sampleRate).toInt())
        val noiseDbCandidates = mutableListOf<Double>()
        val allDbCandidates = mutableListOf<Double>()

        var i = 0
        while (i + fftSize <= maxNoiseSamples) {
            val frame = x.copyOfRange(i, i + fftSize)
            val db = rmsDb(frame)
            val flat = spectralFlatness(frame, window)
            allDbCandidates.add(db)

            // "잡음/무음 성격" 프레임 위주로 noise floor 후보 수집
            if (flat >= 0.75) noiseDbCandidates.add(db)

            i += hop
        }

        val noiseFloorDb = when {
            noiseDbCandidates.isNotEmpty() -> percentile(noiseDbCandidates, 50.0)
            allDbCandidates.isNotEmpty() -> percentile(allDbCandidates, 30.0) // fallback: 낮은 쪽(30%)을 노이즈로 간주
            else -> -120.0
        }

        val requiredDb = max(ABS_DB_THRESHOLD, noiseFloorDb + REL_DB_ABOVE_NOISE)

        if (DEBUG) {
            Log.d(TAG, "noiseFloorDb=$noiseFloorDb requiredDb=$requiredDb sr=$sampleRate")
        }

        // --- Hit window (ratio based) ---
        val hopMs = 1000.0 * hop / sampleRate
        val windowFrames = max(1, (MIN_HIT_MS / hopMs).roundToInt())
        val needHits = ceil(windowFrames * HIT_RATIO).toInt().coerceAtLeast(1)

        val ring = BooleanArray(windowFrames)
        var ringIdx = 0
        var hitCount = 0

        var prevSpecNorm: DoubleArray? = null
        var prevDb = -200.0

        i = 0
        while (i + fftSize <= x.size) {
            val frame = x.copyOfRange(i, i + fftSize)

            val db = rmsDb(frame)
            val magPow = powerSpectrum(frame, window) // power spectrum (half bins)
            val flat = spectralFlatnessFromPower(magPow)
            val crest = spectralCrest(magPow)         // max/mean (linear)
            val top = topEnergyRatio(magPow, TOP_BINS_DEFAULT)
            val acPeak = autocorrPeak(frame)

            val specNorm = normalizePower(magPow)
            val flux = if (prevSpecNorm != null) spectralFlux(prevSpecNorm!!, specNorm) else 0.0

            val energyOk = db >= requiredDb
            val tonalOk = (flat <= MAX_FLATNESS) &&
                    (acPeak >= MIN_AC_PEAK) &&
                    (crest >= MIN_CREST) &&
                    (top >= MIN_TOP_RATIO)

            val jumpOk = (db - prevDb) >= JUMP_DB
            val fluxOk = flux >= MIN_FLUX
            val changeOk = fluxOk || jumpOk

            val noiseLike = (flat >= NOISE_FLATNESS) &&
                    (crest <= NOISE_MAX_CREST) &&
                    (top <= NOISE_MAX_TOP_RATIO) &&
                    (acPeak <= NOISE_MAX_AC_PEAK)

            val hit = energyOk && tonalOk && changeOk && !noiseLike

            if (DEBUG) {
                // 과도한 로그를 피하려면 아래 조건을 더 줄이세요.
                val t = i.toDouble() / sampleRate
                if (hit || t < 0.3) {
                    Log.d(
                        TAG,
                        "t=%.3f db=%.1f flat=%.2f crest=%.2f top=%.2f ac=%.2f flux=%.3f " +
                                "energyOk=%s tonalOk=%s noiseLike=%s"
                                    .format(t, db, flat, crest, top, acPeak, flux, energyOk, tonalOk, noiseLike)
                    )
                }
            }

            // --- sliding window hit ratio ---
            val prev = ring[ringIdx]
            if (prev) hitCount--
            ring[ringIdx] = hit
            if (hit) hitCount++
            ringIdx = (ringIdx + 1) % windowFrames

            if (hitCount >= needHits) {
                val onsetSample = i - (windowFrames - 1) * hop
                val onsetSec = onsetSample.toDouble() / sampleRate
                if (DEBUG) Log.e(TAG, "ONSET DETECTED onsetSec=$onsetSec hitCount=$hitCount/$windowFrames")
                return Result(true, onsetSec, noiseFloorDb, requiredDb, "window_hit_ratio")
            }

            prevSpecNorm = specNorm
            prevDb = db
            i += hop
        }

        Log.e(TAG, "NO ONSET within ${ANALYZE_SEC}s")
        return Result(false, null, noiseFloorDb, requiredDb, "no_tonal_onset_within_${ANALYZE_SEC}s")
    }

    // =========================
    // PCM decode (MediaExtractor + MediaCodec)
    // =========================

    private data class PcmMono(val samples: FloatArray, val sampleRate: Int)

    private fun decodeFirstSecondsToMonoFloat(context: Context, uri: Uri, seconds: Double): PcmMono? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (t in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(t)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = t
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null

            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val targetSamples = (seconds * sampleRate).toInt().coerceAtLeast(1)
            val out = FloatArray(targetSamples)
            var outPos = 0

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone && outPos < targetSamples) {
                // feed input
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex) ?: run {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, 0)
                            continue
                        }
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                // drain output
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)

                            // 대부분 PCM16 (android._config-pcm-encoding = 2)
                            val bytes = ByteArray(bufferInfo.size)
                            outBuf.get(bytes)

                            val samplesCount = bytes.size / 2
                            val frames = samplesCount / channels

                            var bi = 0
                            for (f in 0 until frames) {
                                var sum = 0.0
                                for (c in 0 until channels) {
                                    val lo = bytes[bi].toInt() and 0xFF
                                    val hi = bytes[bi + 1].toInt()
                                    val s = (hi shl 8) or lo
                                    val v = (s.toShort().toInt() / 32768.0)
                                    sum += v
                                    bi += 2
                                }
                                val mono = (sum / channels).toFloat()
                                if (outPos < targetSamples) out[outPos++] = mono else break
                            }
                        }

                        codec.releaseOutputBuffer(outIndex, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // ignore
                    }

                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // no output yet
                    }
                }
            }

            runCatching { codec.stop() }
            runCatching { codec.release() }

            return PcmMono(out.copyOf(outPos), sampleRate)

        } catch (e: Exception) {
            Log.e(TAG, "decode failed: ${e.message}", e)
            return null
        } finally {
            runCatching { extractor.release() }
        }
    }

    // =========================
    // Feature extraction helpers
    // =========================

    private fun hannWindow(n: Int): DoubleArray {
        val w = DoubleArray(n)
        for (i in 0 until n) {
            w[i] = 0.5 * (1.0 - cos(2.0 * Math.PI * i / (n - 1)))
        }
        return w
    }

    private fun rmsDb(frame: FloatArray): Double {
        var sum = 0.0
        for (v in frame) sum += (v.toDouble() * v.toDouble())
        val rms = sqrt(sum / frame.size)
        val eps = 1e-12
        return 20.0 * log10(max(rms, eps))
    }

    /** power spectrum (half bins) */
    private fun powerSpectrum(frame: FloatArray, window: DoubleArray): DoubleArray {
        val n = frame.size
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (i in 0 until n) {
            re[i] = frame[i].toDouble() * window[i]
            im[i] = 0.0
        }
        fftRadix2(re, im)

        val half = n / 2
        val pow = DoubleArray(half)
        for (k in 0 until half) {
            val m = hypot(re[k], im[k])
            pow[k] = m * m
        }
        return pow
    }

    /** Spectral flatness from power spectrum: geometric mean / arithmetic mean */
    private fun spectralFlatnessFromPower(pow: DoubleArray): Double {
        var geo = 0.0
        var arith = 0.0
        val eps = 1e-12
        for (p0 in pow) {
            val p = p0 + eps
            geo += ln(p)
            arith += p
        }
        geo = exp(geo / pow.size)
        arith /= pow.size
        return (geo / arith).coerceIn(0.0, 1.0)
    }

    /** Convenience wrapper (kept for compatibility) */
    private fun spectralFlatness(frame: FloatArray, window: DoubleArray): Double {
        return spectralFlatnessFromPower(powerSpectrum(frame, window))
    }

    /** Spectral crest factor (linear): max(power) / mean(power) */
    private fun spectralCrest(pow: DoubleArray): Double {
        var maxP = 0.0
        var sum = 0.0
        for (p in pow) {
            if (p > maxP) maxP = p
            sum += p
        }
        val mean = sum / pow.size
        if (mean <= 1e-18) return 1.0
        return (maxP / mean).coerceAtLeast(1.0)
    }

    private const val TOP_BINS_DEFAULT = 8

    /** Top energy ratio: sum of top K bins / total */
    private fun topEnergyRatio(pow: DoubleArray, topK: Int): Double {
        var total = 0.0
        for (p in pow) total += p
        if (total <= 1e-18) return 0.0

        // pick topK without full sort (simple partial approach; pow size is small enough anyway)
        val k = topK.coerceIn(1, pow.size)
        val sorted = pow.sortedDescending()
        var topSum = 0.0
        for (i in 0 until k) topSum += sorted[i]
        return (topSum / total).coerceIn(0.0, 1.0)
    }

    /**
     * 자기상관 기반 주기성(피치) 지표 (normalized autocorrelation peak)
     */
    private fun autocorrPeak(frame: FloatArray): Double {
        val n = frame.size
        var energy = 0.0
        for (v in frame) energy += v.toDouble() * v.toDouble()
        if (energy < 1e-9) return 0.0

        val minLag = 20
        val maxLag = min(400, n / 2)

        var best = 0.0
        for (lag in minLag..maxLag) {
            var s = 0.0
            var i = 0
            while (i + lag < n) {
                s += frame[i].toDouble() * frame[i + lag].toDouble()
                i++
            }
            val norm = s / energy
            if (norm > best) best = norm
        }
        return best.coerceIn(0.0, 1.0)
    }

    private fun normalizePower(pow: DoubleArray): DoubleArray {
        var sum = 0.0
        for (p in pow) sum += p
        if (sum <= 1e-18) return pow
        val out = DoubleArray(pow.size)
        for (i in pow.indices) out[i] = pow[i] / sum
        return out
    }

    private fun spectralFlux(prev: DoubleArray, cur: DoubleArray): Double {
        var s = 0.0
        val n = min(prev.size, cur.size)
        for (i in 0 until n) {
            val d = cur[i] - prev[i]
            if (d > 0) s += d
        }
        return s
    }

    // =========================
    // Minimal FFT (radix-2)
    // =========================

    private fun fftRadix2(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n and (n - 1) == 0) { "FFT size must be power of 2" }

        // bit-reversal
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wlenRe = cos(ang)
            val wlenIm = sin(ang)

            var i = 0
            while (i < n) {
                var wRe = 1.0
                var wIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * wRe - im[i + k + len / 2] * wIm
                    val vIm = re[i + k + len / 2] * wIm + im[i + k + len / 2] * wRe

                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm

                    val nextWRe = wRe * wlenRe - wIm * wlenIm
                    val nextWIm = wRe * wlenIm + wIm * wlenRe
                    wRe = nextWRe
                    wIm = nextWIm
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return -120.0
        val sorted = values.sorted()
        val idx = ((p / 100.0) * (sorted.size - 1)).roundToInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
