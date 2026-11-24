import SwiftUI

enum EzcallLanguage: String, CaseIterable {
    case en, fa, ar, es, fr, tr, it

    static let preference = "ezcall.language"

    static var current: Self {
        UserDefaults.standard.string(forKey: preference).flatMap(Self.init(rawValue:)) ?? .en
    }

    var name: String {
        switch self {
        case .en: "English"
        case .fa: "فارسی"
        case .ar: "العربية"
        case .es: "Español"
        case .fr: "Français"
        case .tr: "Türkçe"
        case .it: "Italiano"
        }
    }

    var locale: Locale { Locale(identifier: rawValue) }

    var direction: LayoutDirection { self == .fa || self == .ar ? .rightToLeft : .leftToRight }

    var bundle: Bundle? {
        Bundle.main.url(forResource: rawValue, withExtension: "lproj").flatMap { Bundle(url: $0) }
    }
}

func ezText(_ key: String, _ arguments: CVarArg...) -> String {
    guard !key.isEmpty else { return "" }
    let language = EzcallLanguage.current
    let format = language.bundle?.localizedString(forKey: key, value: key, table: nil) ?? key
    return arguments.isEmpty ? format : String(format: format, locale: language.locale, arguments: arguments)
}

struct EzcallLanguagePicker: View {
    @AppStorage(EzcallLanguage.preference) private var language = EzcallLanguage.en

    var body: some View {
        Picker("Language", selection: $language) {
            ForEach(EzcallLanguage.allCases, id: \.self) { choice in
                Text(verbatim: choice.name).tag(choice)
            }
        }
    }
}
