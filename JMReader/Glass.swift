import SwiftUI

extension View {
    /// Liquid Glass surface on iOS 26 (CI builds with Xcode 26.3, verified
    /// 2026-11-02), frosted-material imitation on older systems.
    /// Apply AFTER padding — the glass fills the shape behind the content.
    @ViewBuilder
    func glassSurface<S: Shape>(_ shape: S, interactive: Bool = false) -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffect(interactive ? Glass.regular.interactive() : Glass.regular, in: shape)
        } else {
            self.background(.ultraThinMaterial, in: shape)
                .overlay(shape.stroke(Color.white.opacity(0.15), lineWidth: 1))
                .shadow(color: .black.opacity(0.22), radius: 10, x: 0, y: 4)
        }
    }

    /// iOS 26 tab bars can shrink away while scrolling down.
    @ViewBuilder
    func tabMinimizeOnScroll() -> some View {
        if #available(iOS 26.0, *) {
            self.tabBarMinimizeBehavior(.onScrollDown)
        } else {
            self
        }
    }
}

// MARK: - swipe back

/// When the navigation bar is hidden (reader), UIKit disables the system's
/// interactive pop gesture. This restores it on the enclosing nav controller.
struct PopGestureRestorer: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { RestorerVC() }
    func updateUIViewController(_ vc: UIViewController, context: Context) {}

    final class RestorerVC: UIViewController {
        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            if let nav = navigationController {
                nav.interactivePopGestureRecognizer?.isEnabled = true
                nav.interactivePopGestureRecognizer?.delegate = nil
            }
        }
    }
}

extension View {
    func restoreSwipeBack() -> some View { background(PopGestureRestorer()) }

    /// Swipe rightwards from anywhere on screen (not just the edge) to go
    /// back. Only fires on clearly horizontal drags, so vertical scrolling
    /// is untouched.
    func swipeAnywhereBack(enabled: Bool = true, action: @escaping () -> Void) -> some View {
        simultaneousGesture(
            DragGesture(minimumDistance: 30, coordinateSpace: .local)
                .onEnded { v in
                    guard enabled else { return }
                    if v.translation.width > 90, abs(v.translation.height) < 70 {
                        action()
                    }
                }
        )
    }
}
