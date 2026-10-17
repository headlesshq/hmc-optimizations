# HMC-Optimizations 1.12.2

Skips rendering the frames of the game, like the optimizations of the newer versions.
They can be turned off and on at runtime with HMC-Specifics' `optimize` command.
Screenshots are read synchronously on 1.12.2, so before a screenshot is taken
a single frame is rendered without optimizations.
