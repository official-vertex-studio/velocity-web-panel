require 'net/http'
require 'json'
require 'uri'

base = ENV.fetch('VERTEX_URL').sub(%r{/+$}, '')
key = ENV.fetch('VERTEX_API_KEY')
uri = URI("#{base}/api/v1/stats")
request = Net::HTTP::Get.new(uri, { 'Authorization' => "Bearer #{key}", 'Accept' => 'application/json' })
response = Net::HTTP.start(uri.hostname, uri.port, use_ssl: uri.scheme == 'https', open_timeout: 3, read_timeout: 5) { |http| http.request(request) }
abort("Vertex API HTTP #{response.code}") unless response.is_a?(Net::HTTPSuccess)
stats = JSON.parse(response.body)
puts "Online players: #{stats['onlinePlayers']}"
puts "Backends: #{stats['backendCount']}"
