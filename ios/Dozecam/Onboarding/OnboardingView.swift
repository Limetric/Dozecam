import SwiftUI

struct OnboardingView: View {
    let model: OnboardingModel
    let onFinish: () -> Void

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Image(systemName: "moon.zzz")
                    .font(.system(size: 56))
                    .foregroundStyle(.tint)
                Text("A baby monitor for UniFi Protect cameras")
                    .font(.title2.bold())
                    .multilineTextAlignment(.center)
                Text("Everything stays on your local network. Signing in to your console arrives with onboarding.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                Button("Continue", action: onFinish)
                    .buttonStyle(.borderedProminent)
            }
            .padding()
            .frame(maxWidth: 520)
            .navigationTitle("Welcome")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
