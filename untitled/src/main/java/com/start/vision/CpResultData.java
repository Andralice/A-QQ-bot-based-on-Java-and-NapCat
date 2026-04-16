package com.start.vision;

import lombok.Getter;

public class CpResultData {
    @Getter
    private final String userA;
    @Getter
    private final String userB;
    @Getter
    private final String avatarB;

    public CpResultData(String userA, String userB, String avatarB) {
        this.userA = userA;
        this.userB = userB;
        this.avatarB = avatarB;
    }

}
