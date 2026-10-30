package neiaddon.event;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/** The screen events the addon listens to (NeoForge's names and getters). */
public abstract class ScreenEvent extends Event {

    private final Screen screen;

    protected ScreenEvent(Screen screen) {
        this.screen = screen;
    }

    public Screen getScreen() {
        return screen;
    }

    public static class Render extends ScreenEvent {

        private final GuiGraphicsExtractor graphics;
        private final int mouseX;
        private final int mouseY;
        private final float partialTick;

        protected Render(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            super(screen);
            this.graphics = graphics;
            this.mouseX = mouseX;
            this.mouseY = mouseY;
            this.partialTick = partialTick;
        }

        public GuiGraphicsExtractor getGuiGraphics() {
            return graphics;
        }

        public int getMouseX() {
            return mouseX;
        }

        public int getMouseY() {
            return mouseY;
        }

        public float getPartialTick() {
            return partialTick;
        }

        /** After the screen's main contents and before the deferred tooltips (NeoForge's Render.Foreground). */
        public static final class Foreground extends Render {

            public Foreground(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
                super(screen, graphics, mouseX, mouseY, partialTick);
            }
        }

        /** After the screen drew everything (the foreground included). */
        public static final class Post extends Render {

            public Post(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
                super(screen, graphics, mouseX, mouseY, partialTick);
            }
        }
    }

    /** Events that carry the mouse position (scaled GUI coordinates). */
    public abstract static class MouseInput extends ScreenEvent {

        private final double mouseX;
        private final double mouseY;

        protected MouseInput(Screen screen, double mouseX, double mouseY) {
            super(screen);
            this.mouseX = mouseX;
            this.mouseY = mouseY;
        }

        public double getMouseX() {
            return mouseX;
        }

        public double getMouseY() {
            return mouseY;
        }
    }

    public static final class MouseButtonPressed {

        private MouseButtonPressed() {}

        public static final class Pre extends MouseInput {

            private final int button;

            public Pre(Screen screen, double mouseX, double mouseY, int button) {
                super(screen, mouseX, mouseY);
                this.button = button;
            }

            public int getButton() {
                return button;
            }
        }
    }

    public static final class MouseButtonReleased {

        private MouseButtonReleased() {}

        public static final class Pre extends MouseInput {

            private final int button;

            public Pre(Screen screen, double mouseX, double mouseY, int button) {
                super(screen, mouseX, mouseY);
                this.button = button;
            }

            public int getButton() {
                return button;
            }
        }
    }

    public static final class MouseDragged {

        private MouseDragged() {}

        public static final class Pre extends MouseInput {

            private final int button;
            private final double dragX;
            private final double dragY;

            public Pre(Screen screen, double mouseX, double mouseY, int button, double dragX, double dragY) {
                super(screen, mouseX, mouseY);
                this.button = button;
                this.dragX = dragX;
                this.dragY = dragY;
            }

            public int getMouseButton() {
                return button;
            }

            public double getDragX() {
                return dragX;
            }

            public double getDragY() {
                return dragY;
            }
        }
    }

    public static final class MouseScrolled {

        private MouseScrolled() {}

        public static final class Pre extends MouseInput {

            private final double scrollX;
            private final double scrollY;

            public Pre(Screen screen, double mouseX, double mouseY, double scrollX, double scrollY) {
                super(screen, mouseX, mouseY);
                this.scrollX = scrollX;
                this.scrollY = scrollY;
            }

            public double getScrollDeltaX() {
                return scrollX;
            }

            public double getScrollDeltaY() {
                return scrollY;
            }
        }
    }

    /** Key events: key code, scan code and modifier bits. */
    public abstract static class KeyInput extends ScreenEvent {

        private final int keyCode;
        private final int scanCode;
        private final int modifiers;

        protected KeyInput(Screen screen, int keyCode, int scanCode, int modifiers) {
            super(screen);
            this.keyCode = keyCode;
            this.scanCode = scanCode;
            this.modifiers = modifiers;
        }

        public int getKeyCode() {
            return keyCode;
        }

        public int getScanCode() {
            return scanCode;
        }

        public int getModifiers() {
            return modifiers;
        }
    }

    public static final class KeyPressed {

        private KeyPressed() {}

        public static final class Pre extends KeyInput {

            public Pre(Screen screen, int keyCode, int scanCode, int modifiers) {
                super(screen, keyCode, scanCode, modifiers);
            }
        }
    }

    public static final class KeyReleased {

        private KeyReleased() {}

        public static final class Pre extends KeyInput {

            public Pre(Screen screen, int keyCode, int scanCode, int modifiers) {
                super(screen, keyCode, scanCode, modifiers);
            }
        }
    }

    public static final class CharacterTyped {

        private CharacterTyped() {}

        public static final class Pre extends ScreenEvent {

            private final int codePoint;
            private final int modifiers;

            public Pre(Screen screen, int codePoint, int modifiers) {
                super(screen);
                this.codePoint = codePoint;
                this.modifiers = modifiers;
            }

            public int getCodePoint() {
                return codePoint;
            }

            public int getModifiers() {
                return modifiers;
            }
        }
    }

    /** A screen is about to be shown (the new one may be null: the screen closes). */
    public static final class Opening extends ScreenEvent {

        private final Screen newScreen;

        public Opening(Screen current, Screen newScreen) {
            super(current);
            this.newScreen = newScreen;
        }

        public Screen getCurrentScreen() {
            return getScreen();
        }

        public Screen getNewScreen() {
            return newScreen;
        }
    }
}
