document.addEventListener('DOMContentLoaded', () => {
    const activityItems = document.querySelectorAll('.activity-item');

    activityItems.forEach(item => {
        const activityName = item.querySelector('.activity-name');
        const activityDetails = item.querySelector('.activity-details');
        const mapContainer = item.querySelector('.map-container');
        const imageCarousel = item.querySelector('.image-carousel');
        const carouselNav = item.querySelector('.carousel-nav'); // Select the nav container
        const location = item.dataset.location;
        const images = JSON.parse(item.dataset.images || '[]');

        activityName.addEventListener('click', () => {
            const isOpen = item.classList.contains('open');

            // Close all other open activities
            document.querySelectorAll('.activity-item.open').forEach(openItem => {
                if (openItem !== item) {
                    openItem.classList.remove('open');
                    openItem.querySelector('.activity-details').classList.remove('expanded');
                    // Clear map and images from closed items
                    openItem.querySelector('.map-container').innerHTML = '';
                    openItem.querySelector('.image-carousel').innerHTML = '';
                    openItem.querySelector('.carousel-nav').innerHTML = ''; // Clear nav buttons
                }
            });

            // Toggle current activity
            item.classList.toggle('open');
            activityDetails.classList.toggle('expanded');

            if (item.classList.contains('open')) {
                // Load map without API key
                loadMap(mapContainer, location);
                // Load images
                loadImageCarousel(imageCarousel, carouselNav, images); // Pass carouselNav
            } else {
                // Clear map and images when closing
                mapContainer.innerHTML = '';
                imageCarousel.innerHTML = '';
                carouselNav.innerHTML = ''; // Clear nav buttons
            }
        });
    });

    function loadMap(container, location) {
        // Using Google Maps' standard embed URL. No API key needed for basic embeds.
        // The 'q' parameter is for query, which can be an address or place name.
        const encodedLocation = encodeURIComponent(location);
        container.innerHTML = `<iframe
            width="100%"
            height="100%"
            frameborder="0" style="border:0"
            src="https://maps.google.com/maps?q=${encodedLocation}&output=embed"
            allowfullscreen>
        </iframe>`;
    }

    function loadImageCarousel(imageContainer, navContainer, imageUrls) {
        imageContainer.innerHTML = ''; // Clear previous images
        navContainer.innerHTML = ''; // Clear previous buttons

        if (imageUrls.length === 0) {
            imageContainer.innerHTML = '<p>No images available.</p>';
            return;
        }

        imageUrls.forEach(url => {
            const imgWrapper = document.createElement('div');
            imgWrapper.classList.add('carousel-image-wrapper');
            const img = document.createElement('img');
            img.src = url;
            img.alt = 'Venue image';
            // Add error handling for images (optional, but good practice)
            img.onerror = () => { img.src = 'https://via.placeholder.com/200x150?text=Image+Not+Found'; img.alt = 'Image failed to load'; };
            imgWrapper.appendChild(img);
            imageContainer.appendChild(imgWrapper);
        });

        // Add carousel navigation if more than one image
        if (imageUrls.length > 1) {
            const prevButton = document.createElement('button');
            prevButton.textContent = 'Previous';
            const nextButton = document.createElement('button');
            nextButton.textContent = 'Next';

            prevButton.addEventListener('click', () => {
                imageContainer.scrollBy({ left: -imageContainer.offsetWidth, behavior: 'smooth' });
            });
            nextButton.addEventListener('click', () => {
                imageContainer.scrollBy({ left: imageContainer.offsetWidth, behavior: 'smooth' });
            });

            navContainer.appendChild(prevButton);
            navContainer.appendChild(nextButton);
        }
    }
});
