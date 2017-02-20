DP_VERSION_NUMBER="1.0.1"

if ENV['DP_VERSION_NUMBER']
DP_VERSION_NUMBER = "#{ENV['DP_VERSION_NUMBER']}"
end

repositories.remote << "http://www.intalio.org/public/maven2"

# We need to download the artifact before we load the same
artifact("org.intalio.common.dependencies:openrepo:rb:#{DP_VERSION_NUMBER}").invoke
artifact("org.intalio.common.dependencies:intaliorepo:rb:#{DP_VERSION_NUMBER}").invoke
artifact("org.intalio.common.dependencies:version:rb:#{DP_VERSION_NUMBER}").invoke
artifact("org.intalio.common.dependencies:common:rb:#{DP_VERSION_NUMBER}").invoke
artifact("org.intalio.common.dependencies:intalio:rb:#{DP_VERSION_NUMBER}").invoke
artifact("org.intalio.common.dependencies:intaliorelease:rb:#{DP_VERSION_NUMBER}").invoke

OPENREPO = "#{ENV['HOME']}/.m2/repository/org/intalio/common/dependencies/openrepo/#{DP_VERSION_NUMBER}/openrepo-#{DP_VERSION_NUMBER}.rb"
if ENV["M2_REPO"]
  OPENREPO = "#{ENV['M2_REPO']}/org/intalio/common/dependencies/openrepo/#{DP_VERSION_NUMBER}/openrepo-#{DP_VERSION_NUMBER}.rb"
end
load OPENREPO

INTREPO = "#{ENV['HOME']}/.m2/repository/org/intalio/common/dependencies/intaliorepo/#{DP_VERSION_NUMBER}/intaliorepo-#{DP_VERSION_NUMBER}.rb"
if ENV["M2_REPO"]
  INTREPO = "#{ENV['M2_REPO']}/org/intalio/common/dependencies/intaliorepo/#{DP_VERSION_NUMBER}/intaliorepo-#{DP_VERSION_NUMBER}.rb"
end
load INTREPO

VERSIONS = "#{ENV['HOME']}/.m2/repository/org/intalio/common/dependencies/version/#{DP_VERSION_NUMBER}/version-#{DP_VERSION_NUMBER}.rb"
if ENV["M2_REPO"]
  VERSIONS = "#{ENV['M2_REPO']}/org/intalio/common/dependencies/version/#{DP_VERSION_NUMBER}/version-#{DP_VERSION_NUMBER}.rb"
end
load VERSIONS

OPENSOURCE = "#{ENV['HOME']}/.m2/repository/org/intalio/common/dependencies/common/#{DP_VERSION_NUMBER}/common-#{DP_VERSION_NUMBER}.rb"
if ENV["M2_REPO"]
  OPENSOURCE = "#{ENV['M2_REPO']}/org/intalio/common/dependencies/common/#{DP_VERSION_NUMBER}/common-#{DP_VERSION_NUMBER}.rb"
end
load OPENSOURCE

DEPENDENCIES = "#{ENV['HOME']}/.m2/repository/org/intalio/common/dependencies/intalio/#{DP_VERSION_NUMBER}/intalio-#{DP_VERSION_NUMBER}.rb"
if ENV["M2_REPO"]
  DEPENDENCIES = "#{ENV['M2_REPO']}/org/intalio/common/dependencies/intalio/#{DP_VERSION_NUMBER}/intalio-#{DP_VERSION_NUMBER}.rb"
end
load DEPENDENCIES

INTRELEASE = "#{ENV['HOME']}/.m2/repository/org/intalio/common/dependencies/intaliorelease/#{DP_VERSION_NUMBER}/intaliorelease-#{DP_VERSION_NUMBER}.rb"
if ENV["M2_REPO"]
  INTRELEASE = "#{ENV['M2_REPO']}/org/intalio/common/dependencies/intaliorelease/#{DP_VERSION_NUMBER}/intaliorelease-#{DP_VERSION_NUMBER}.rb"
end
load INTRELEASE