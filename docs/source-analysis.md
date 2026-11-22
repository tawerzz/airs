# Airs Audio 2.1.0 source analysis

Source examined: user-provided `ibhmgglejliilciffebcbnklceoblcbe.zip`.

The archive is a packaged Manifest V3 extension. Its executable audio implementation is in `assets/offscreen-Bq7BRSS9.js`, with UI/state logic in `assets/popup-DBjJTEeo.js` and parameter helper code in `assets/qCalculations-Bd0SWPGM.js`.

## Verified signal path

`MediaStreamSource -> 13 BiquadFilterNodes in series -> GainNode -> AudioContext.destination`.
The `AnalyserNode` is connected from the GainNode, so the visible spectrum observes the post-EQ, post-output-gain signal.

## Filter layout and types

Initial centre frequencies (Hz):

`5, 10, 20, 40, 80, 160, 320, 640, 1280, 2560, 5120, 10240, 20480`.

- Node 0: peaking.
- Node 1: peaking.
- Node 2: lowshelf.
- Nodes 3–11: peaking.
- Node 12: highshelf.

The points are not locked to fixed frequency bands. In the popup they can be dragged horizontally from 1 Hz to 21,500 Hz and vertically from -30 dB to +30 dB. Shift-drag adjusts the base Q from 0.1 to 2.0.

## Q parameter rule

The helper module defines default peaking base Q `0.3`, shelf Q parameter `0.75`, and factor `2`:

`Qeffective = Qbase * 2^(1 - 2 * abs(gainDb) / 30)` for peaking filters.

Shelf filters use their base Q unchanged. During initial offscreen graph creation, peaking nodes are created with Q=0.3 and shelf nodes with Q=0.75. The UI derives base Q from the actual returned values when it reads the graph.

## Bass Boost button

The exact popup handler calls `ac({2: 5}, {2: 120}, {2: 0.75})`. This resets the other filter gains/frequencies to defaults, sets node index 2 (the lowshelf) to +5 dB at 120 Hz, and reconstructs the Q values/base-Q values using the helper's defaults (0.3 for peaking filters, 0.75 for shelves).

## Spectrum analyser

The offscreen code sets `fftSize = 2048` and `smoothingTimeConstant = 0.85`. `getByteFrequencyData` returns 1024 bins.

## Output gain

The GainNode is after the 13 EQ filters. The popup's vertical volume control maps to approximately -30 dB through +30 dB, with a special minimum value of 0.0001 (-80 dB) at the very bottom. There is no compressor or limiter in the inspected audio graph.

## Port notes

The Android app uses one PCM audio processor with 13 cascaded biquads and a final gain stage, preserving the original order. Peaking and shelf coefficients follow the Web Audio specification; shelf coefficient calculations use S=1 and correctly ignore Q. The source code does not supply an Android app or a native DSP library; this is an implementation port of the packaged Web Audio logic. Exact sample-for-sample identity across browser and Android audio backends cannot be claimed without an A/B reference test on the same source material and sample-rate conditions.

## Important shelf-curve detail

The popup bundle's own SVG response-curve calculation uses the node Q value when it calculates its displayed shelf curve. The actual Web Audio shelf node, however, follows the Web Audio coefficient equations where the shelf slope is fixed at S=1 and Q is unused. The Android graph is intentionally calculated from the actual Web Audio filter coefficients that process the sound, rather than reproducing that small mismatch in the browser popup's drawing code.
