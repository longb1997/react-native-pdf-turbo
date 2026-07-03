require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name         = "ReactNativePdfTurbo"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.description  = <<-DESC
                  High-performance PDF viewer for React Native
                   DESC
  s.homepage     = "https://github.com/longb1997/react-native-pdf-turbo"
  s.license      = "MIT"
  s.author       = { "Long Hoang" => "hoanglonghaui97@gmail.com" }
  s.platform     = :ios, "12.0"
  s.source       = { :git => "https://github.com/longb1997/react-native-pdf-turbo.git", :tag => "v#{s.version}" }
  s.source_files = "ios/**/*.{swift,h,m,mm,cpp}"
  s.requires_arc = true
  s.swift_version = "5.0"

  # New Architecture (Fabric): pull in the Fabric/codegen dependencies and set
  # the RCT_NEW_ARCH_ENABLED flag when the app has the New Architecture enabled.
  # Falls back to plain React-Core on the old architecture.
  if respond_to?(:install_modules_dependencies, true)
    install_modules_dependencies(s)
  else
    s.dependency "React-Core"
  end
end