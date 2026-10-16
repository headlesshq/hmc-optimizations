# HMC-Optimizations 1.7.10

Skips rendering the frames of the game, like the optimizations of the newer versions.
They can be turned off and on at runtime with HMC-Specifics' `optimize` command.
Screenshots are read synchronously on 1.7.10 (F2 and HMC-Specifics, which reads the pixels itself),
so before a screenshot is taken a single frame is rendered without optimizations.
