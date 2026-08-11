package com.astrocompass.catalog

import astrocompass.composeapp.generated.resources.Res
import astrocompass.composeapp.generated.resources.*
import org.jetbrains.compose.resources.DrawableResource

/**
 * Looks up the bundled photo for a Messier-numbered [DeepSkyObject], or `null` if this object has
 * no Messier number ([DeepSkyObject.messier] == 0) or no photo was sourced for it. Compose
 * Multiplatform generates one named `Res.drawable.mN` accessor per bundled file (see
 * `composeResources/drawable/`) rather than an indexable collection, so this is a hand-mapped
 * (mechanically generated from `tools/fetch-object-images.mjs`'s manifest, not hand-typed) lookup
 * rather than a loop -- covers all Messier objects with a sourced photo except M102, which OpenNGC
 * itself excludes (see dso.bin).
 */
fun messierImageDrawable(messierNumber: Int): DrawableResource? = when (messierNumber) {
    1 -> Res.drawable.m1
    2 -> Res.drawable.m2
    3 -> Res.drawable.m3
    4 -> Res.drawable.m4
    5 -> Res.drawable.m5
    6 -> Res.drawable.m6
    7 -> Res.drawable.m7
    8 -> Res.drawable.m8
    9 -> Res.drawable.m9
    10 -> Res.drawable.m10
    11 -> Res.drawable.m11
    12 -> Res.drawable.m12
    13 -> Res.drawable.m13
    14 -> Res.drawable.m14
    15 -> Res.drawable.m15
    16 -> Res.drawable.m16
    17 -> Res.drawable.m17
    18 -> Res.drawable.m18
    19 -> Res.drawable.m19
    20 -> Res.drawable.m20
    21 -> Res.drawable.m21
    22 -> Res.drawable.m22
    23 -> Res.drawable.m23
    24 -> Res.drawable.m24
    25 -> Res.drawable.m25
    26 -> Res.drawable.m26
    27 -> Res.drawable.m27
    28 -> Res.drawable.m28
    29 -> Res.drawable.m29
    30 -> Res.drawable.m30
    31 -> Res.drawable.m31
    32 -> Res.drawable.m32
    33 -> Res.drawable.m33
    34 -> Res.drawable.m34
    35 -> Res.drawable.m35
    36 -> Res.drawable.m36
    37 -> Res.drawable.m37
    38 -> Res.drawable.m38
    39 -> Res.drawable.m39
    40 -> Res.drawable.m40
    41 -> Res.drawable.m41
    42 -> Res.drawable.m42
    43 -> Res.drawable.m43
    44 -> Res.drawable.m44
    45 -> Res.drawable.m45
    46 -> Res.drawable.m46
    47 -> Res.drawable.m47
    48 -> Res.drawable.m48
    49 -> Res.drawable.m49
    50 -> Res.drawable.m50
    51 -> Res.drawable.m51
    52 -> Res.drawable.m52
    53 -> Res.drawable.m53
    54 -> Res.drawable.m54
    55 -> Res.drawable.m55
    56 -> Res.drawable.m56
    57 -> Res.drawable.m57
    58 -> Res.drawable.m58
    59 -> Res.drawable.m59
    60 -> Res.drawable.m60
    61 -> Res.drawable.m61
    62 -> Res.drawable.m62
    63 -> Res.drawable.m63
    64 -> Res.drawable.m64
    65 -> Res.drawable.m65
    66 -> Res.drawable.m66
    67 -> Res.drawable.m67
    68 -> Res.drawable.m68
    69 -> Res.drawable.m69
    70 -> Res.drawable.m70
    71 -> Res.drawable.m71
    72 -> Res.drawable.m72
    73 -> Res.drawable.m73
    74 -> Res.drawable.m74
    75 -> Res.drawable.m75
    76 -> Res.drawable.m76
    77 -> Res.drawable.m77
    78 -> Res.drawable.m78
    79 -> Res.drawable.m79
    80 -> Res.drawable.m80
    81 -> Res.drawable.m81
    82 -> Res.drawable.m82
    83 -> Res.drawable.m83
    84 -> Res.drawable.m84
    85 -> Res.drawable.m85
    86 -> Res.drawable.m86
    87 -> Res.drawable.m87
    88 -> Res.drawable.m88
    89 -> Res.drawable.m89
    90 -> Res.drawable.m90
    91 -> Res.drawable.m91
    92 -> Res.drawable.m92
    93 -> Res.drawable.m93
    94 -> Res.drawable.m94
    95 -> Res.drawable.m95
    96 -> Res.drawable.m96
    97 -> Res.drawable.m97
    98 -> Res.drawable.m98
    99 -> Res.drawable.m99
    100 -> Res.drawable.m100
    101 -> Res.drawable.m101
    103 -> Res.drawable.m103
    104 -> Res.drawable.m104
    105 -> Res.drawable.m105
    106 -> Res.drawable.m106
    107 -> Res.drawable.m107
    108 -> Res.drawable.m108
    109 -> Res.drawable.m109
    110 -> Res.drawable.m110
    else -> null
}
