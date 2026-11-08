package io.papermc.paperclip;

import com.extantape.preflight.upgrade.FakePaper;

/** Test only: lets the fake server jar look like a Paper (paperclip) jar. */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        FakePaper.main(args);
    }
}
